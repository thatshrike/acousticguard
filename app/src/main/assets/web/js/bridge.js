/**
 * AcousticGuard Android Bridge Interface
 * Safely wraps native Android calls and provides graceful fallbacks for web/debug preview.
 */
(function(window) {
  'use strict';

  const hasNativeBridge = () => Boolean(window.AndroidBridge);

  window.AGBridge = {
    toggleMonitoring: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.toggleMonitoring === 'function') {
        window.AndroidBridge.toggleMonitoring();
      } else {
        console.warn('[AGBridge] toggleMonitoring called without native bridge');
      }
    },

    cancelSosAlert: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.cancelSosAlert === 'function') {
        window.AndroidBridge.cancelSosAlert();
      } else {
        console.warn('[AGBridge] cancelSosAlert called');
      }
    },

    simulateDistress: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.simulateDistress === 'function') {
        window.AndroidBridge.simulateDistress();
      } else {
        console.warn('[AGBridge] simulateDistress called');
      }
    },

    setQuietAlertMode: function(enabled) {
      if (hasNativeBridge() && typeof window.AndroidBridge.setQuietAlertMode === 'function') {
        window.AndroidBridge.setQuietAlertMode(Boolean(enabled));
      }
    },

    setCommunityMeshEnabled: function(enabled) {
      if (hasNativeBridge() && typeof window.AndroidBridge.setCommunityMeshEnabled === 'function') {
        window.AndroidBridge.setCommunityMeshEnabled(Boolean(enabled));
      }
    },

    saveQuickPin: function(pin) {
      if (hasNativeBridge() && typeof window.AndroidBridge.saveQuickPin === 'function') {
        window.AndroidBridge.saveQuickPin(String(pin));
      }
    },

    saveCircleSettings: function(phone, endpoint) {
      if (hasNativeBridge() && typeof window.AndroidBridge.saveCircleSettings === 'function') {
        window.AndroidBridge.saveCircleSettings(String(phone), String(endpoint));
      }
    },

    recordSafeSample: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.recordSafeSample === 'function') {
        window.AndroidBridge.recordSafeSample();
      }
    },

    startOwnerVoiceEnrollment: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.startOwnerVoiceEnrollment === 'function') {
        window.AndroidBridge.startOwnerVoiceEnrollment();
      }
    },

    clearOwnerVoiceProfile: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.clearOwnerVoiceProfile === 'function') {
        window.AndroidBridge.clearOwnerVoiceProfile();
      }
    },

    requestOverlay: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.requestOverlay === 'function') {
        window.AndroidBridge.requestOverlay();
      }
    },

    requestExactAlarm: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.requestExactAlarm === 'function') {
        window.AndroidBridge.requestExactAlarm();
      }
    },

    requestBatteryExemption: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.requestBatteryExemption === 'function') {
        window.AndroidBridge.requestBatteryExemption();
      }
    },

    rotateKey: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.rotateKey === 'function') {
        return window.AndroidBridge.rotateKey();
      }
      return 1;
    },

    getInitialState: function() {
      if (hasNativeBridge() && typeof window.AndroidBridge.getInitialState === 'function') {
        try {
          return JSON.parse(window.AndroidBridge.getInitialState());
        } catch (e) {
          console.error('[AGBridge] Failed to parse initial state:', e);
        }
      }
      return null;
    },

    triggerHaptic: function(pattern) {
      if (navigator.vibrate) {
        try {
          navigator.vibrate(pattern || 15);
        } catch (_) {}
      }
    }
  };
})(window);
