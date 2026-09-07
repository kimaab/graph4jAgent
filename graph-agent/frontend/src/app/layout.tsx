import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import Link from "next/link";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "Agent Studio",
  description: "Define LangGraph agents in the browser, run them, and export the code",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col bg-zinc-50 text-zinc-900 dark:bg-zinc-950 dark:text-zinc-100">
        <header className="border-b border-zinc-200 bg-white dark:border-zinc-800 dark:bg-zinc-900">
          <div className="mx-auto flex max-w-6xl items-center gap-3 px-6 py-3">
            {/*
              `Link`, not a bare `<a>`: basePath is applied to next/link and to nothing
              else, so a raw anchor keeps the literal "/agents" and, under the platform's
              sub-path, sends the browser to the platform root instead of to this app.
              Every other link here already went through Link; this one did not, and it
              is in the header of every page.
            */}
            <Link href="/agents" className="text-sm font-semibold tracking-tight">
              Agent Studio
            </Link>
            <span className="text-xs text-zinc-500">
              LangGraph agents, defined in the browser
            </span>
            <nav className="ml-auto flex items-center gap-4 text-xs">
              <Link href="/agents" className="text-zinc-600 hover:underline dark:text-zinc-400">
                에이전트
              </Link>
              <Link
                href="/datasources"
                className="text-zinc-600 hover:underline dark:text-zinc-400"
              >
                데이터소스
              </Link>
            </nav>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">{children}</main>
      </body>
    </html>
  );
}
