"use client";

import "./globals.css";
import { ThemeProvider } from "next-themes";
import { AuthProvider } from "./components/AuthProvider";
import AppShell from "./components/AppShell";

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <head>
        <title>AI-JobPilot-Cloud｜投递牛马云端版</title>
        <meta name="description" content="投递牛马 SaaS 云端版开发迁移基线，集中管理求职资料、岗位、AI 匹配与投递清单" />
        <link
          rel="icon"
          href="/toudi-niuma.svg"
          type="image/svg+xml"
        />
      </head>
      <body suppressHydrationWarning className="bg-[#f7faff] dark:bg-blacksection">
        <ThemeProvider
          attribute="class"
          defaultTheme="light"
          enableSystem={false}
        >
          <AuthProvider>
            <AppShell>{children}</AppShell>
          </AuthProvider>
        </ThemeProvider>
      </body>
    </html>
  );
}
