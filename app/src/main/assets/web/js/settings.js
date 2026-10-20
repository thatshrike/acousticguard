/**
 * AcousticGuard Settings & Citadel Management
 */
(function(window) {
  'use strict';

  let currentEnteredPin = "";

  function initSettings() {
    // 1. Ghost Alert (Quiet Mode) Switch
    const ghostSwitch = document.getElementById('ghostSwitch');
    if (ghostSwitch) {
      ghostSwitch.addEventListener('click', () => {
        const nextState = !ghostSwitch.classList.contains('active');
        ghostSwitch.classList.toggle('active', nextState);
        ghostSwitch.setAttribute('aria-checked', String(nextState));
        if (window.AGBridge) window.AGBridge.setQuietAlertMode(nextState);
      });
    }

    // 2. Community BLE Mesh Switch
    const meshSwitch = document.getElementById('meshSwitch');
    if (meshSwitch) {
      meshSwitch.addEventListener('click', () => {
        const nextState = !meshSwitch.classList.contains('active');
        meshSwitch.classList.toggle('active', nextState);
        meshSwitch.setAttribute('aria-checked', String(nextState));
        if (window.AGBridge) window.AGBridge.setCommunityMeshEnabled(nextState);
      });
    }

    // 3. Theme Toggle (Dark / Light)
    const themeSwitch = document.getElementById('themeSwitch');
    if (themeSwitch) {
      themeSwitch.addEventListener('click', () => {
        const html = document.documentElement;
        const isLight = html.classList.toggle('light');
        html.classList.toggle('dark', !isLight);
        themeSwitch.classList.toggle('active', isLight);
        themeSwitch.setAttribute('aria-checked', String(isLight));
        localStorage.setItem('ag_theme', isLight ? 'light' : 'dark');
        if (window.AGBridge) window.AGBridge.triggerHaptic(15);
      });

      // Restore saved theme
      const savedTheme = localStorage.getItem('ag_theme');
      if (savedTheme === 'light') {
        document.documentElement.classList.add('light');
        document.documentElement.classList.remove('dark');
        themeSwitch.classList.add('active');
      }
    }

    // 4. Safety Circle Inputs & Save
    const saveCircleBtn = document.getElementById('btnSaveCircle');
    const phoneInput = document.getElementById('inputCirclePhone');
    const endpointInput = document.getElementById('inputCircleEndpoint');
    const circleStatusMsg = document.getElementById('circleStatusMsg');

    if (saveCircleBtn) {
      saveCircleBtn.addEventListener('click', () => {
        const phone = phoneInput ? phoneInput.value.trim() : '';
        const endpoint = endpointInput ? endpointInput.value.trim() : '';
        if (window.AGBridge) {
          window.AGBridge.saveCircleSettings(phone, endpoint);
          if (circleStatusMsg) {
            circleStatusMsg.textContent = 'Safety contacts saved.';
            setTimeout(() => { circleStatusMsg.textContent = ''; }, 3000);
          }
        }
      });
    }

    // 5. Safe Sample Calibration Button
    const recordSafeBtn = document.getElementById('btnRecordSafeSample');
    if (recordSafeBtn) {
      recordSafeBtn.addEventListener('click', () => {
        if (window.AGBridge) {
          window.AGBridge.recordSafeSample();
          recordSafeBtn.textContent = 'Calibrating...';
          recordSafeBtn.disabled = true;
          setTimeout(() => {
            recordSafeBtn.textContent = 'Record Safe Sample';
            recordSafeBtn.disabled = false;
          }, 6000);
        }
      });
    }

    // 6. Owner Voice Enrollment
    const ownerVoiceActionBtn = document.getElementById('btnOwnerVoiceAction');
    const clearOwnerVoiceBtn = document.getElementById('btnClearOwnerVoice');

    if (ownerVoiceActionBtn) {
      ownerVoiceActionBtn.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.startOwnerVoiceEnrollment();
      });
    }

    if (clearOwnerVoiceBtn) {
      clearOwnerVoiceBtn.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.clearOwnerVoiceProfile();
      });
    }

    // 7. System Permission Buttons
    const btnReqOverlay = document.getElementById('btnReqOverlay');
    if (btnReqOverlay) {
      btnReqOverlay.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.requestOverlay();
      });
    }

    const btnReqExactAlarm = document.getElementById('btnReqExactAlarm');
    if (btnReqExactAlarm) {
      btnReqExactAlarm.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.requestExactAlarm();
      });
    }

    const btnReqBattery = document.getElementById('btnReqBattery');
    if (btnReqBattery) {
      btnReqBattery.addEventListener('click', () => {
        if (window.AGBridge) window.AGBridge.requestBatteryExemption();
      });
    }

    const btnRotateKey = document.getElementById('btnRotateKey');
    const keyVersionText = document.getElementById('keyVersionText');
    if (btnRotateKey) {
      btnRotateKey.addEventListener('click', () => {
        if (window.AGBridge) {
          const v = window.AGBridge.rotateKey();
          if (keyVersionText) keyVersionText.textContent = `Key Version ${v} (StrongBox / Keystore)`;
        }
      });
    }

    // 8. Keypad Setup for Quick Duress PIN Modal
    initPinKeypad();
  }

  function initPinKeypad() {
    const pinDots = document.getElementById('pinDots');
    const keys = document.querySelectorAll('.pin-key');
    const clearBtn = document.getElementById('btnPinClear');
    const savePinBtn = document.getElementById('btnSavePin');

    keys.forEach(key => {
      key.addEventListener('click', () => {
        const val = key.getAttribute('data-val');
        if (!val || currentEnteredPin.length >= 8) return;
        currentEnteredPin += val;
        updatePinDisplay();
        if (window.AGBridge) window.AGBridge.triggerHaptic(12);
      });
    });

    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        currentEnteredPin = currentEnteredPin.slice(0, -1);
        updatePinDisplay();
        if (window.AGBridge) window.AGBridge.triggerHaptic(10);
      });
    }

    if (savePinBtn) {
      savePinBtn.addEventListener('click', () => {
        if (currentEnteredPin.length < 4) return;
        if (window.AGBridge) {
          window.AGBridge.saveQuickPin(currentEnteredPin);
          if (window.AGNav) window.AGNav.closeModal(document.getElementById('pinModal'));
        }
        currentEnteredPin = '';
        updatePinDisplay();
      });
    }

    function updatePinDisplay() {
      if (pinDots) {
        pinDots.textContent = currentEnteredPin.length > 0 ? '•'.repeat(currentEnteredPin.length) : 'Enter 4-8 digits';
      }
    }
  }

  // Owner Voice Profile Telemetry Hook
  window.updateOwnerVoiceProfile = function(profile) {
    const pill = document.getElementById('ownerVoicePill');
    const statusText = document.getElementById('ownerVoiceStatusText');
    const actionBtn = document.getElementById('btnOwnerVoiceAction');
    const clearBtn = document.getElementById('btnClearOwnerVoice');

    if (!profile) return;

    if (profile.enrolled) {
      if (pill) {
        pill.textContent = 'Enrolled';
        pill.style.color = 'var(--emerald)';
      }
      if (statusText) statusText.textContent = `${profile.sampleCount || 1}/3 samples captured. Voice features stay on-device.`;
      if (actionBtn) actionBtn.textContent = 'Update Owner Voice Profile';
      if (clearBtn) clearBtn.style.display = 'inline-flex';
    } else if (profile.status === 'enrolling') {
      if (pill) {
        pill.textContent = 'Recording...';
        pill.style.color = 'var(--gold)';
      }
      if (statusText) statusText.textContent = `Speak naturally into microphone (${profile.remaining || 0} frames remaining)...`;
      if (actionBtn) actionBtn.textContent = 'Enrolling...';
    } else {
      if (pill) {
        pill.textContent = 'Not enrolled';
        pill.style.color = 'var(--text-tertiary)';
      }
      if (statusText) statusText.textContent = profile.error || 'No owner voice enrolled. Ready for on-device calibration.';
      if (actionBtn) actionBtn.textContent = 'Start Owner Voice Enrollment';
      if (clearBtn) clearBtn.style.display = 'none';
    }
  };

  // Safe Sample Calibration Telemetry Hook
  window.updateCalibration = function(sampleCount) {
    const badge = document.getElementById('calibrationBadge');
    if (badge) badge.textContent = `${sampleCount}/3 samples`;
  };

  // Full Native State Sync
  window.initFromNative = function(state) {
    if (!state) return;
    if (typeof state.isMonitoring === 'boolean' && window.AGDashboard) {
      window.AGDashboard.setGuarding(state.isMonitoring);
    }

    const ghostSwitch = document.getElementById('ghostSwitch');
    if (ghostSwitch && typeof state.quietAlertMode === 'boolean') {
      ghostSwitch.classList.toggle('active', state.quietAlertMode);
    }

    const meshSwitch = document.getElementById('meshSwitch');
    if (meshSwitch && typeof state.communityMeshEnabled === 'boolean') {
      meshSwitch.classList.toggle('active', state.communityMeshEnabled);
    }

    const phoneInput = document.getElementById('inputCirclePhone');
    if (phoneInput && state.phoneNumber) phoneInput.value = state.phoneNumber;

    const endpointInput = document.getElementById('inputCircleEndpoint');
    if (endpointInput && state.httpsEndpoint) endpointInput.value = state.httpsEndpoint;

    if (typeof state.safeSamplesCount === 'number') {
      window.updateCalibration(state.safeSamplesCount);
    }

    if (state.ownerVoiceProfile) {
      window.updateOwnerVoiceProfile(state.ownerVoiceProfile);
    }

    const keyVersionText = document.getElementById('keyVersionText');
    if (keyVersionText && state.keyVersion) {
      keyVersionText.textContent = `Key Version ${state.keyVersion} (StrongBox / Keystore)`;
    }

    const btnReqOverlay = document.getElementById('btnReqOverlay');
    if (btnReqOverlay && state.hasOverlay) btnReqOverlay.textContent = 'Granted';

    const btnReqExactAlarm = document.getElementById('btnReqExactAlarm');
    if (btnReqExactAlarm && state.hasExactAlarm) btnReqExactAlarm.textContent = 'Granted';

    const btnReqBattery = document.getElementById('btnReqBattery');
    if (btnReqBattery && state.isIgnoringBattery) btnReqBattery.textContent = 'Granted';
  };

  window.AGSettings = {
    init: initSettings
  };

  document.addEventListener('DOMContentLoaded', initSettings);
})(window);
