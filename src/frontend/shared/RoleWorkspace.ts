export type WorkspaceNavigationItem = {
  label: string;
  href: string;
  current?: boolean;
};

type ElementFactory<Element> = (
  type: string,
  props: Record<string, unknown> | null,
  ...children: unknown[]
) => Element;

/** Builds the common role workspace without coupling the shared source to an app package. */
export function createRoleWorkspace<Element>(
  createElement: ElementFactory<Element>,
  options: {
    role: string;
    identity?: string;
    authenticated: boolean;
    navigation: WorkspaceNavigationItem[];
    accountActions?: Element;
    children: Element;
  },
): Element {
  const navigation = options.authenticated
    ? createElement(
        "nav",
        {
          className: "role-workspace__navigation",
          "aria-label": `${options.role} navigation`,
        },
      createElement("a", { href: "/account/", className: "role-workspace__navigation-link" }, "Account"),
        ...options.navigation.map((item) =>
          createElement(
            "a",
            {
              href: item.href,
              className: "role-workspace__navigation-link",
              ...(item.current ? { "aria-current": "page" } : {}),
            },
            item.label,
          ),
        ),
      )
    : null;

  return createElement(
    "div",
    {
      className: `role-workspace${options.authenticated ? "" : " role-workspace--anonymous"}`,
    },
    createElement(
      "a",
      {
        className: "role-workspace__skip-link",
        href: "#workspace-content",
      },
      "Skip to main content",
    ),
    createElement(
      "header",
      { className: "role-workspace__header" },
      createElement(
        "div",
        { className: "role-workspace__brand" },
        createElement("span", { className: "role-workspace__brand-name" }, "Bug Tracker"),
        options.authenticated
          ? createElement("span", { className: "role-workspace__role" }, options.role)
          : null,
      ),
      options.authenticated
        ? createElement(
            "div",
            { className: "role-workspace__account" },
            options.identity
              ? createElement("span", null, options.identity)
              : null,
            options.accountActions ?? null,
          )
        : null,
    ),
    navigation,
    createElement(
      "div",
      {
        className: "role-workspace__content",
        id: "workspace-content",
        tabIndex: -1,
      },
      options.children,
    ),
  );
}
