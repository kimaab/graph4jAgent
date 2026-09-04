"use client";

import { useParams, usePathname } from "next/navigation";

/**
 * The placeholder the static export writes for the `[id]` segment.
 * Kept in step with `app/agents/[id]/layout.tsx`.
 */
const SHELL_ID = "_id";

/**
 * The agent id this page is for, or null while it is not yet knowable.
 *
 * An agent id is a UUID the server assigns, so there is no set of them to prerender and
 * every `/agents/<id>` URL is served the one shell page built for the placeholder. That
 * page's baked param is the placeholder, so `useParams()` returns `_id` and the address
 * bar is where the real id lives.
 *
 * `usePathname()` rather than `window.location.pathname`: the latter is read during
 * render and, on a client-side navigation out of the list page, still holds `/agents/`
 * — with no id segment to match, the hook fell back to `_id` and the page asked the API
 * for an agent by that name. The request 400s, and the editor showed
 * "'agent_id' is not a valid value: _id" and stayed there. `usePathname()` is router
 * state, so it re-renders the hook when the route commits.
 *
 * Returning null for the one render where neither source has the id yet is the point:
 * a caller that skips fetching on null cannot fire that doomed request at all, whatever
 * order the router happens to update in.
 */
export function useAgentId(): string | null {
  const { id } = useParams<{ id: string }>();
  const pathname = usePathname();

  if (id && id !== SHELL_ID) {
    return id;
  }

  const match = pathname?.match(/\/agents\/([^/]+)/);
  const fromPath = match ? decodeURIComponent(match[1]) : null;
  return fromPath && fromPath !== SHELL_ID ? fromPath : null;
}
