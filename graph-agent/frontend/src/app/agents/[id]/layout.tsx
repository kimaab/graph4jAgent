/**
 * Exists only to give the static export something to write for this segment.
 *
 * An agent id is a UUID the server assigns, so there is no set of them to
 * enumerate at build time. One placeholder page is emitted instead, and the
 * Python backend serves it for every `/agents/<id>` request; the page reads the
 * real id off the URL once it is running.
 *
 * The page itself is a client component and cannot export this — `generateStaticParams`
 * has to come from a server file, which is what this layout is.
 */
export const SHELL_ID = "_id";

export function generateStaticParams() {
  return [{ id: SHELL_ID }];
}

export default function AgentLayout({ children }: { children: React.ReactNode }) {
  return children;
}
