/**
 * AcousticGuard Dashboard — 32-Bar Real-Time Visualizer & Telemetry Pipeline
 */
(function(window) {
  'use strict';

  let isMonitoringActive = false;
  let currentDb = 14.0;
  let targetDb = 14.0;
  let lastTelemetryTimestamp = Date.now();

  const NUM_BARS = 32;
  const barHeights = new Float32Array(NUM_BARS).fill(4);
  const targetHeights = new Float32Array(NUM_BARS).fill(4);

  // Cached DOM elements
  let heroCard, heroTitle, heroSubtitle, heroDbVal, heroBrandDot, mainToggleBtn;
  let tierOneVal, neuralVal, fusionVal;
  let visualizerContainer;
  let timelineContainer;

  function initDashboard() {
    heroCard = document.getElementById('heroCard');
    heroTitle = document.getElementById('heroTitle');
    heroSubtitle = document.getElementById('heroSubtitle');
    heroDbVal = document.getElementById('heroDbVal');
    heroBrandDot = document.getElementById('brandDot');
    mainToggleBtn = document.getElementById('btnToggleMonitoring');

    tierOneVal = document.getElementById('metricTierOne');
    neuralVal = document.getElementById('metricNeural');
    fusionVal = document.getElementById('metricFusion');

    visualizerContainer = document.getElementById('spectrumVisualizer');
    timelineContainer = document.getElementById('statusTimeline');

    createVisualizerBars();
    startAnimationLoop();

    if (mainToggleBtn) {
      mainToggleBtn.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.toggleMonitoring();
      });
    }

    const testSosBtn = document.getElementById('btnTestSos');
    if (testSosBtn) {
      testSosBtn.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.simulateDistress();
      });
    }
  }

  function createVisualizerBars() {
    if (!visualizerContainer) return;
    visualizerContainer.innerHTML = '';
    for (let i = 0; i < NUM_BARS; i++) {
      const bar = document.createElement('div');
      bar.className = 'spectrum-bar';
      bar.id = `specBar_${i}`;
      visualizerContainer.appendChild(bar);
    }
  }

  function startAnimationLoop() {
    function animate() {
      // Smooth dB numeric transition
      currentDb += (targetDb - currentDb) * 0.18;
      if (heroDbVal) {
        heroDbVal.textContent = isMonitoringActive ? Math.max(12, currentDb).toFixed(1) : '--';
      }

      // 32-bar visualizer spring physics
      for (let i = 0; i < NUM_BARS; i++) {
        barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.22;
        const barEl = document.getElementById(`specBar_${i}`);
        if (barEl) {
          barEl.style.height = `${Math.max(4, barHeights[i])}px`;
        }
      }

      // Natural idle drift if telemetry is idle
      if (isMonitoringActive && Date.now() - lastTelemetryTimestamp > 2000) {
        targetDb = 18.0 + Math.sin(Date.now() / 600) * 3.0;
        for (let i = 0; i < NUM_BARS; i++) {
          const centerDist = Math.abs(i - NUM_BARS / 2) / (NUM_BARS / 2);
          targetHeights[i] = Math.max(4, (1.0 - centerDist) * 16 + Math.sin(Date.now() / 300 + i * 0.3) * 5);
        }
      }

      requestAnimationFrame(animate);
    }
    requestAnimationFrame(animate);
  }

  function setHeroState(state, customSubtitle) {
    if (!heroCard) return;
    heroCard.classList.remove('state-idle', 'state-monitoring', 'state-candidate', 'state-prealert');
    if (heroBrandDot) heroBrandDot.classList.remove('candidate', 'alert');

    switch (state) {
      case 'IDLE':
        heroCard.classList.add('state-idle');
        if (heroTitle) heroTitle.textContent = 'Protection Paused';
        if (heroSubtitle) heroSubtitle.textContent = 'Tap to activate autonomous on-device distress monitoring.';
        if (mainToggleBtn) mainToggleBtn.textContent = 'Activate Protection';
        targetHeights.fill(4);
        break;

      case 'MONITORING':
        heroCard.classList.add('state-monitoring');
        if (heroTitle) heroTitle.textContent = 'Sentinel Active';
        if (heroSubtitle) heroSubtitle.textContent = customSubtitle || 'Continuous on-device neural acoustic evaluation in ephemeral memory.';
        if (mainToggleBtn) mainToggleBtn.textContent = 'Pause Protection';
        break;

      case 'CANDIDATE':
        heroCard.classList.add('state-candidate');
        if (heroBrandDot) heroBrandDot.classList.add('candidate');
        if (heroTitle) {
          heroTitle.textContent = 'Acoustic Anomaly';
          heroTitle.style.color = 'var(--amber)';
        }
        if (heroSubtitle) heroSubtitle.textContent = customSubtitle || 'Elevated high-frequency / transient energy detected. Running neural evaluation.';
        break;

      case 'PRE_ALERT':
        heroCard.classList.add('state-prealert');
        if (heroBrandDot) heroBrandDot.classList.add('alert');
        if (heroTitle) {
          heroTitle.textContent = 'Distress Validated';
          heroTitle.style.color = 'var(--ruby)';
        }
        if (heroSubtitle) heroSubtitle.textContent = customSubtitle || 'Emergency grace period opened. Verifying community dispatch.';
        break;
    }
  }

  function addTimelineEvent(text, severity = 'emerald') {
    if (!timelineContainer) return;
    const item = document.createElement('div');
    item.className = `timeline-item ${severity}`;
    const timeStr = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    item.textContent = `${timeStr} — ${text}`;
    timelineContainer.prepend(item);

    // Keep only last 10 entries
    while (timelineContainer.children.length > 10) {
      timelineContainer.removeChild(timelineContainer.lastChild);
    }
  }

  // Native Telemetry Ingestion Hook
  window.updateTelemetry = function(db, candidate, fastTrack, details) {
    lastTelemetryTimestamp = Date.now();
    if (!isMonitoringActive) return;

    targetDb = Math.max(12, db + 100); // map -100..0 dB to visual 0..100 dB scale

    const normalized = Math.max(0, Math.min(1, (db + 70) / 70));

    // Distribute acoustic spectrum across 32 bars with bell-curve shaping
    for (let i = 0; i < NUM_BARS; i++) {
      const centerFactor = 1.0 - Math.abs(i - NUM_BARS / 2) / (NUM_BARS / 2);
      const jitter = (Math.random() - 0.5) * 8;
      targetHeights[i] = Math.max(4, Math.min(48, (normalized * 44 * centerFactor) + jitter));
    }

    if (candidate) {
      setHeroState('CANDIDATE', fastTrack ? 'Fast-track vocal friction + respiratory coupling anomaly.' : 'Acoustic candidate flagged.');
    } else {
      setHeroState('MONITORING');
    }

    // Optional richer telemetry payload
    if (details && typeof details === 'object') {
      if (tierOneVal && typeof details.tierOneScore === 'number') {
        tierOneVal.textContent = `${(details.tierOneScore * 100).toFixed(0)}%`;
      }
      if (neuralVal && typeof details.neuralScore === 'number') {
        neuralVal.textContent = `${(details.neuralScore * 100).toFixed(0)}%`;
      }
      if (fusionVal && typeof details.fusionScore === 'number') {
        fusionVal.textContent = `${(details.fusionScore * 100).toFixed(0)}%`;
      }
    }
  };

  window.updatePipelineStage = function(stage) {
    if (!isMonitoringActive) return;
    const stageMap = {
      rapid_probe: { text: 'Rapid neural probe evaluating spike', state: 'CANDIDATE', sev: 'amber' },
      rapid_validated: { text: 'Rapid distress validated', state: 'PRE_ALERT', sev: 'ruby' },
      standard_inference: { text: 'Full-window neural inference running', state: 'CANDIDATE', sev: 'amber' },
      standard_validated: { text: 'Distress confirmed; opening grace period', state: 'PRE_ALERT', sev: 'ruby' }
    };

    const entry = stageMap[stage];
    if (entry) {
      setHeroState(entry.state, entry.text);
      addTimelineEvent(entry.text, entry.sev);
    }
  };

  window.setDashboardGuarding = function(active) {
    isMonitoringActive = Boolean(active);
    setHeroState(isMonitoringActive ? 'MONITORING' : 'IDLE');
    if (!isMonitoringActive) {
      targetDb = 0;
      targetHeights.fill(4);
    }
  };

  window.AGDashboard = {
    init: initDashboard,
    setGuarding: window.setDashboardGuarding,
    addTimelineEvent: addTimelineEvent
  };

  document.addEventListener('DOMContentLoaded', initDashboard);
})(window);
