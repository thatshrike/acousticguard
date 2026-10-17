package com.acousticguard

import java.text.Normalizer
import java.util.Locale

/**
 * Language-independent events consumed by the reasoning layer.
 *
 * The model's output labels are evidence, not policy. A label is mapped to one
 * of these events before it can participate in a rapid decision. This keeps
 * policy from depending on a substring or on a particular language.
 */
enum class DistressEvent {
    BENIGN_SPEECH,
    STRONG_NON_VERBAL_DISTRESS,
    IMPACT,
    EXPLICIT_DISTRESS,
    DURESS
}

enum class IndianLanguage {
    HINDI,
    BENGALI,
    TAMIL,
    TELUGU,
    KANNADA,
    MALAYALAM,
    MARATHI,
    GUJARATI,
    PUNJABI,
    URDU
}

/** A typed interpretation of one model label. */
data class NormalizedDistressLabel(
    val canonical: String,
    val event: DistressEvent,
    val language: IndianLanguage? = null
)

/** A model label and its calibrated score, retained as data for policy. */
data class ModelLabelScore(
    val label: String,
    val score: Float
)

/**
 * Exact label taxonomy for the checked-in model contract.
 *
 * Aliases include common native-script and transliterated Indian-language
 * phrases. The lookup is exact after conservative Unicode/punctuation
 * normalization; it intentionally does not use substring matching. This is
 * a vocabulary, not a claim that every language or dialect is covered.
 */
object DistressLabelTaxonomy {
    private data class Alias(
        val event: DistressEvent,
        val canonical: String,
        val language: IndianLanguage? = null
    )

    private val aliases: Map<String, Alias> = buildMap {
        add(DistressEvent.BENIGN_SPEECH, "benign_speech", null, "benign speech")
        add(DistressEvent.BENIGN_SPEECH, "speech", null, "normal speech", "conversation", "talking")

        add(
            DistressEvent.STRONG_NON_VERBAL_DISTRESS,
            "distress",
            null,
            "distress", "scream", "screaming", "cry", "crying", "sob", "sobbing",
            "hyperventilation", "struggle", "screaming vocal friction", "hyperventilation gasp",
            "physical restraint groan", "suffocation stridor", "strong non verbal distress", "panic"
        )
        add(
            DistressEvent.IMPACT,
            "impact",
            null,
            "impact", "thud", "physical struggle thud", "fall impact"
        )
        add(
            DistressEvent.DURESS,
            "duress",
            null,
            "duress", "coercion", "forced speech", "silent distress", "code word", "emergency code",
            "not safe", "under threat"
        )

        // English explicit distress phrases.
        addExplicit(null, "help", "help me", "please help", "save me", "call the police")

        // Hindi: native script and commonly used Latin transliterations.
        addExplicit(IndianLanguage.HINDI, "मदद", "मदद करो", "मेरी मदद करो", "मुझे बचाओ", "बचाओ", "सहायता")
        addExplicit(IndianLanguage.HINDI, "madad", "madad karo", "meri madad karo", "mujhe bachao", "bachao", "bachaao", "sahayata", "sahaaya")

        // Bengali.
        addExplicit(IndianLanguage.BENGALI, "সাহায্য", "সাহায্য করো", "আমাকে সাহায্য করো", "বাঁচাও", "আমাকে বাঁচাও")
        addExplicit(IndianLanguage.BENGALI, "sahajjo", "sahajjo koro", "amake sahajjo koro", "amake bachao", "amader bachao", "banchao")

        // Tamil.
        addExplicit(IndianLanguage.TAMIL, "உதவி", "உதவி செய்யுங்கள்", "என்னை காப்பாற்றுங்கள்", "காப்பாற்றுங்கள்", "காப்பாத்துங்க")
        addExplicit(IndianLanguage.TAMIL, "udhavi", "udhavi seiyungal", "ennai kaappatrungal", "kaappatrungal", "kaappathunga", "udavi")

        // Telugu.
        addExplicit(IndianLanguage.TELUGU, "సహాయం", "సహాయం చేయండి", "నన్ను కాపాడండి", "కాపాడండి")
        addExplicit(IndianLanguage.TELUGU, "sahayam", "sahayam cheyandi", "nannu kapadandi", "kapadandi")

        // Kannada.
        addExplicit(IndianLanguage.KANNADA, "ಸಹಾಯ", "ಸಹಾಯ ಮಾಡಿ", "ನನ್ನನ್ನು ಕಾಪಾಡಿ", "ಕಾಪಾಡಿ")
        addExplicit(IndianLanguage.KANNADA, "sahaya", "sahaya maadi", "nannannu kapadi", "kapadi")

        // Malayalam.
        addExplicit(IndianLanguage.MALAYALAM, "സഹായം", "സഹായിക്കൂ", "എന്നെ രക്ഷിക്കൂ", "രക്ഷിക്കൂ")
        addExplicit(IndianLanguage.MALAYALAM, "sahayam", "sahayikku", "enne rakshikku", "rakshikku")

        // Marathi.
        addExplicit(IndianLanguage.MARATHI, "मदत", "मदत करा", "मला वाचवा", "वाचवा")
        addExplicit(IndianLanguage.MARATHI, "madat", "madat kara", "mala vachva", "vachva")

        // Gujarati.
        addExplicit(IndianLanguage.GUJARATI, "મદદ", "મદદ કરો", "મને બચાવો", "બચાવો")
        addExplicit(IndianLanguage.GUJARATI, "madad", "madad karo", "mane bachavo", "bachavo")

        // Punjabi.
        addExplicit(IndianLanguage.PUNJABI, "ਮਦਦ", "ਮਦਦ ਕਰੋ", "ਮੈਨੂੰ ਬਚਾਓ", "ਬਚਾਓ")
        addExplicit(IndianLanguage.PUNJABI, "madad", "madad karo", "mainu bachao", "bachao")

        // Urdu.
        addExplicit(IndianLanguage.URDU, "مدد", "مدد کرو", "مجھے بچاؤ", "بچاؤ")
        addExplicit(IndianLanguage.URDU, "madad", "madad karo", "mujhe bachao", "bachao")
    }

    /** Returns a typed label only when the complete normalized label is known. */
    fun normalize(rawLabel: String): NormalizedDistressLabel? {
        val key = normalizeKey(rawLabel)
        if (key.isEmpty()) return null
        return aliases[key]?.let { NormalizedDistressLabel(it.canonical, it.event, it.language) }
    }

    /** Normalization used for exact matching, exposed for diagnostics/tests. */
    fun normalizeKey(rawLabel: String): String {
        if (rawLabel.isBlank()) return ""
        return Normalizer.normalize(rawLabel, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(Regex("[\\p{Punct}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun MutableMap<String, Alias>.add(
        event: DistressEvent,
        canonical: String,
        language: IndianLanguage?,
        vararg labels: String
    ) {
        labels.forEach { label ->
            val key = normalizeKey(label)
            check(key.isNotEmpty()) { "Taxonomy labels must not be empty" }
            val previous = get(key)
            val mergedLanguage = when {
                previous == null -> language
                previous.event != event -> null
                previous.language == language -> language
                else -> null // Shared transliterations are intentionally ambiguous.
            }
            put(key, Alias(event, canonical, mergedLanguage))
        }
    }

    private fun MutableMap<String, Alias>.addExplicit(language: IndianLanguage?, vararg labels: String) =
        add(DistressEvent.EXPLICIT_DISTRESS, "explicit distress", language, *labels)
}
