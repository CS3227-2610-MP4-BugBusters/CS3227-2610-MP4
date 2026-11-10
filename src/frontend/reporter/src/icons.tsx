// Small original line icons, drawn on a 16px grid.

type Props = { className?: string; title?: string };

function Svg({ className, title, children }: Props & { children: React.ReactNode }) {
  return (
    <svg
      className={className}
      width="16"
      height="16"
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
    >
      {children}
    </svg>
  );
}

export function OpenIcon(props: Props) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6.25" />
      <circle cx="8" cy="8" r="1.5" fill="currentColor" stroke="none" />
    </Svg>
  );
}

export function ClosedIcon(props: Props) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6.25" />
      <path d="M5.25 8.25l1.9 1.9 3.6-3.9" />
    </Svg>
  );
}

export function CommentIcon(props: Props) {
  return (
    <Svg {...props}>
      <path d="M2.75 3.25h10.5v7.5H8l-3 2.5v-2.5H2.75z" />
    </Svg>
  );
}

export function BugIcon(props: Props) {
  return (
    <Svg {...props}>
      <ellipse cx="8" cy="9" rx="3.5" ry="4.25" />
      <path d="M8 4.75V13.25M4.5 9H1.75M14.25 9H11.5M5 5.5L3 3.75M11 5.5l2-1.75M5 12.5l-2 1.5M11 12.5l2 1.5" />
    </Svg>
  );
}

export function PencilIcon(props: Props) {
  return (
    <Svg {...props}>
      <path d="M10.75 2.75l2.5 2.5-7.5 7.5H3.25v-2.5z" />
    </Svg>
  );
}
