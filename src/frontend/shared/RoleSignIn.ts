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
    createElement("p", { className: "role-sign-in__eyebrow" }, `${options.role.toUpperCase()} WORKSPACE`),
    createElement("h2", null, "Sign in"),
    createElement("p", { className: "role-sign-in__intro" }, options.description),
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
