import Link from "next/link";

/**
 * Replaces Next's built-in not-found page.
 *
 * The default one ships a `<a href="/">Back</a>`, which under the platform's sub-path
 * points at the platform root rather than at this app — and the upload check flags it.
 * Going through `Link` is what applies basePath.
 */
export default function NotFound() {
  return (
    <div className="py-16 text-center">
      <h1 className="text-2xl font-semibold tracking-tight">찾을 수 없는 페이지</h1>
      <p className="mt-2 text-sm text-zinc-500">
        주소가 바뀌었거나, 지워진 에이전트일 수 있습니다.
      </p>
      <Link
        href="/agents"
        className="mt-6 inline-block rounded-md bg-zinc-900 px-4 py-2 text-sm font-medium text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
      >
        에이전트 목록으로
      </Link>
    </div>
  );
}
