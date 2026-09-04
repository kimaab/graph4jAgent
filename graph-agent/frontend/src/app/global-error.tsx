"use client";

/**
 * Replaces Next's built-in global error screen.
 *
 * The default one navigates with `window.location.href = "/"` when history is empty,
 * which under the platform's sub-path leaves the app for the platform root. Going back
 * through history is safe, and the reload path stays on the current URL.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="ko">
      <body
        style={{
          fontFamily: "system-ui, sans-serif",
          padding: "4rem 1.5rem",
          textAlign: "center",
        }}
      >
        <h1 style={{ fontSize: "1.5rem", fontWeight: 600 }}>문제가 생겼습니다</h1>
        <p style={{ marginTop: ".5rem", fontSize: ".875rem", color: "#71717a" }}>
          {error.message || "알 수 없는 오류"}
          {error.digest ? ` (${error.digest})` : ""}
        </p>
        <div style={{ marginTop: "1.5rem", display: "flex", gap: ".5rem", justifyContent: "center" }}>
          <button
            type="button"
            onClick={reset}
            style={{
              borderRadius: 6,
              background: "#18181b",
              color: "white",
              padding: ".5rem 1rem",
              fontSize: ".875rem",
            }}
          >
            다시 시도
          </button>
          <button
            type="button"
            // No absolute path: reloading stays on whatever sub-path the app is mounted at.
            onClick={() => window.location.reload()}
            style={{
              borderRadius: 6,
              border: "1px solid #d4d4d8",
              padding: ".5rem 1rem",
              fontSize: ".875rem",
            }}
          >
            새로고침
          </button>
        </div>
      </body>
    </html>
  );
}
