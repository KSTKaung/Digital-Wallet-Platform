import type { ReactNode, Ref } from 'react';

/**
 * Frame for the signed-out and account pages: a labelled section with a focusable h1, so a page
 * can move focus to its heading after a step completes.
 */
export function AuthPage({
  id,
  title,
  headingRef,
  children,
}: {
  id: string;
  title: string;
  headingRef?: Ref<HTMLHeadingElement>;
  children: ReactNode;
}) {
  const headingId = `${id}-heading`;
  return (
    <section aria-labelledby={headingId} className="mx-auto max-w-md space-y-5">
      <h1
        id={headingId}
        ref={headingRef}
        tabIndex={-1}
        className="text-xl font-semibold focus:outline-none"
      >
        {title}
      </h1>
      {children}
    </section>
  );
}

export const FORM_CLASSES = 'space-y-4';
