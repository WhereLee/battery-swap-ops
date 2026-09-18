import type { Directive } from "vue";
import type { useAuthStore } from "../stores/auth";

type AuthStore = ReturnType<typeof useAuthStore>;
type AccessValue = string | string[] | undefined;

/**
 * v-access — button-level permission gate.
 *
 *   <el-button v-access="'admin:suggestion:manage'">确认执行</el-button>
 *   <el-button v-access="['admin:alarm:handle', 'admin:work-order:manage']">…</el-button>
 *
 * Semantics are ANY-OF, matching router meta.codes and the backend
 * @PreAuthorize("hasAnyAuthority(...)"). An empty/undefined value renders the element
 * (nothing to gate).
 *
 * The element is hidden with display:none rather than detached so the directive stays
 * correct when the binding value changes (v-for rows reuse elements). This is a UX
 * gate only: hiding a button in the DOM is not a security boundary — the backend
 * enforces the same codes and returns 403 regardless.
 */
export function createAccessDirective(auth: AuthStore): Directive<HTMLElement, AccessValue> {
  function toggle(el: HTMLElement, value: AccessValue): void {
    const allowed = auth.hasAnyCode(value);
    if (allowed) {
      if (el.dataset.accessHidden === "1") {
        el.style.display = el.dataset.accessDisplay ?? "";
        delete el.dataset.accessHidden;
        delete el.dataset.accessDisplay;
      }
      return;
    }
    if (el.dataset.accessHidden !== "1") {
      el.dataset.accessDisplay = el.style.display;
      el.dataset.accessHidden = "1";
    }
    el.style.display = "none";
  }

  return {
    mounted: (el, binding) => toggle(el, binding.value),
    updated: (el, binding) => toggle(el, binding.value),
  };
}
