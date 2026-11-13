type ElementFactory<Element> = (
  type: string,
  props: Record<string, unknown> | null,
  ...children: unknown[]
) => Element;

/** Builds the shared sign-in presentation without coupling it to a UI framework. */
export function createRoleSignIn<Element>(
  createElement: ElementFactory<Element>,
  options: {
    role: string;
    description: string;
    username: string;
    password: string;
    busy: boolean;
    requiredCredentials?: boolean;
    submitLabel?: string;
    busyLabel?: string;
    onUsernameChange: (value: string) => void;
    onPasswordChange: (value: string) => void;
    onSubmit: unknown;
    notice?: Element | null;
    status?: Element | null;
    feedback?: Element | null;
  },
): Element {
  const credentialInput = (
    label: string,
    id: string,
    name: string,
    autoComplete: string,
    value: string,
    onChange: (value: string) => void,
    type = "text",
  ) =>
    createElement(
      "label",
      { htmlFor: id },
      label,
      createElement("input", {
        id,
        name,
        type,
        autoComplete,
        required: options.requiredCredentials ?? false,
        value,
        disabled: options.busy,
        onChange: (event: { target: { value: string } }) => onChange(event.target.value),
      }),
    );

  return createElement(
    "section",
    { className: "role-sign-in", "aria-label": "Sign in" },
    createElement("svg", { className: "role-sign-in__logo", viewBox: "0 0 16 16", fill: "none", stroke: "currentColor", strokeWidth: "1.5", strokeLinecap: "round", strokeLinejoin: "round", "aria-hidden": "true" },
      createElement("ellipse", { cx: "8", cy: "9", rx: "3.5", ry: "4.25" }),
      createElement("path", { d: "M8 4.75V13.25M4.5 9H1.75M14.25 9H11.5M5 5.5L3 3.75M11 5.5l2-1.75M5 12.5l-2 1.5M11 12.5l2 1.5" }),
    ),
    createElement("h1", null, options.role === "Bug Tracker" ? "Sign in to Bug Tracker" : `Sign in to ${options.role.toLowerCase()} workspace`),
    options.notice ?? null,
    createElement(
      "form",
      { onSubmit: options.onSubmit },
      credentialInput("Username", "role-sign-in-username", "username", "username", options.username, options.onUsernameChange),
      credentialInput("Password", "role-sign-in-password", "password", "current-password", options.password, options.onPasswordChange, "password"),
      createElement(
        "button",
        { type: "submit", disabled: options.busy },
        options.busy ? options.busyLabel ?? options.submitLabel ?? "Sign in" : options.submitLabel ?? "Sign in",
      ),
      options.status ?? null,
    ),
    options.feedback ?? null,
  );
}
