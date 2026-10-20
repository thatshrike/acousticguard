/**
 * AcousticGuard Navigation & Modal Manager
 */
(function(window) {
  'use strict';

  function initNavigation() {
    const navItems = document.querySelectorAll('.bottom-nav .nav-item');
    const tabs = document.querySelectorAll('.tab-content');

    navItems.forEach(item => {
      item.addEventListener('click', () => {
        const targetTabId = item.getAttribute('data-tab');
        if (!targetTabId) return;

        navItems.forEach(n => {
          n.classList.remove('active');
          n.setAttribute('aria-selected', 'false');
        });
        tabs.forEach(t => t.classList.remove('active'));

        item.classList.add('active');
        item.setAttribute('aria-selected', 'true');

        const activeTab = document.getElementById(targetTabId);
        if (activeTab) {
          activeTab.classList.add('active');
          window.scrollTo({ top: 0, behavior: 'smooth' });
        }

        if (window.AGBridge) window.AGBridge.triggerHaptic(12);
      });
    });
  }

  // Generic modal opening / closing with accessible focus management
  function openModal(modalEl) {
    if (!modalEl) return;
    modalEl.classList.add('open');
    modalEl.setAttribute('aria-hidden', 'false');
    const firstFocusable = modalEl.querySelector('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
    if (firstFocusable) firstFocusable.focus();
    if (window.AGBridge) window.AGBridge.triggerHaptic(15);
  }

  function closeModal(modalEl) {
    if (!modalEl) return;
    modalEl.classList.remove('open');
    modalEl.setAttribute('aria-hidden', 'true');
    if (window.AGBridge) window.AGBridge.triggerHaptic(10);
  }

  window.AGNav = {
    init: initNavigation,
    openModal: openModal,
    closeModal: closeModal
  };

  document.addEventListener('DOMContentLoaded', initNavigation);
})(window);
