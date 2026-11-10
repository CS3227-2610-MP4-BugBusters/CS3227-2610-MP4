/** "← Your reports / current page" trail shown above a report or the new-report form. */
export function Breadcrumbs({ backHref, current }: { backHref: string; current: string }) {
  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      <a className="breadcrumbs-back" href={backHref}>
        <span aria-hidden="true">←</span> Your reports
      </a>
      <span className="breadcrumbs-sep" aria-hidden="true">
        /
      </span>
      <span className="breadcrumbs-current" aria-current="page">
        {current}
      </span>
    </nav>
  );
}
