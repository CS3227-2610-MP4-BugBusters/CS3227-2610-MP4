import { useEffect, useId, useRef, useState } from "react";
import type { KeyboardEvent } from "react";
import { MarkdownView } from "./MarkdownView";

export interface MarkdownEditorProps {
  value: string;
  onChange: (next: string) => void;
  /** Accessible name of the source textarea. */
  label: string;
}

type Tab = "write" | "preview";

const TABS: readonly Tab[] = ["write", "preview"];
const TAB_NAMES: Record<Tab, string> = { write: "Write", preview: "Preview" };

/**
 * Controlled Write/Preview editor (MDR-06). Preview renders the shared
 * MarkdownView directly so it matches what every role sees. Switching tabs
 * never calls onChange.
 */
export function MarkdownEditor({ value, onChange, label }: MarkdownEditorProps) {
  const baseId = useId();
  const [active, setActive] = useState<Tab>("write");
  const tabRefs = useRef<Record<Tab, HTMLButtonElement | null>>({ write: null, preview: null });
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const returnedToWrite = useRef(false);

  const tabId = (tab: Tab) => `${baseId}-tab-${tab}`;
  const panelId = `${baseId}-panel`;

  const select = (tab: Tab) => {
    if (tab === active) return;
    returnedToWrite.current = tab === "write";
    setActive(tab);
  };

  useEffect(() => {
    if (active === "write" && returnedToWrite.current) {
      returnedToWrite.current = false;
      textareaRef.current?.focus();
    }
  }, [active]);

  const onTabKeyDown = (event: KeyboardEvent<HTMLButtonElement>, tab: Tab) => {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const step = event.key === "ArrowRight" ? 1 : -1;
    const index = (TABS.indexOf(tab) + step + TABS.length) % TABS.length;
    tabRefs.current[TABS[index]]?.focus();
  };

  return (
    <div>
      <div role="tablist">
        {TABS.map((tab) => (
          <button
            key={tab}
            ref={(el) => {
              tabRefs.current[tab] = el;
            }}
            type="button"
            role="tab"
            id={tabId(tab)}
            aria-selected={active === tab}
            aria-controls={panelId}
            tabIndex={active === tab ? 0 : -1}
            onClick={() => select(tab)}
            onKeyDown={(event) => onTabKeyDown(event, tab)}
          >
            {TAB_NAMES[tab]}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={panelId} aria-labelledby={tabId(active)}>
        {active === "write" ? (
          <textarea
            ref={textareaRef}
            aria-label={label}
            value={value}
            onChange={(event) => onChange(event.target.value)}
          />
        ) : value.trim() === "" ? (
          "Nothing to preview"
        ) : (
          <MarkdownView source={value} />
        )}
      </div>
    </div>
  );
}
