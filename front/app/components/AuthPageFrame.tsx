"use client"

import Image from "next/image"
import Link from "next/link"

export default function AuthPageFrame({
  title,
  description,
  children,
  footer,
}: {
  title: string
  description: string
  children: React.ReactNode
  footer: React.ReactNode
}) {
  return (
    <main className="min-h-screen bg-[radial-gradient(circle_at_top_left,_rgba(59,130,246,0.14),_transparent_36rem),linear-gradient(180deg,#f8fbff,#eff6ff)] px-4 py-10 dark:bg-blacksection">
      <div className="mx-auto flex min-h-[calc(100vh-5rem)] max-w-5xl items-center justify-center">
        <div className="grid w-full overflow-hidden rounded-3xl border border-white/80 bg-white/90 shadow-[0_30px_90px_rgba(37,99,235,0.16)] backdrop-blur-xl md:grid-cols-[0.9fr_1.1fr] dark:border-white/10 dark:bg-slate-950/90">
          <section className="hidden bg-gradient-to-br from-blue-600 to-indigo-700 p-10 text-white md:flex md:flex-col md:justify-between">
            <div>
              <Link href="/" className="inline-flex items-center gap-3">
                <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-white/95 shadow-lg">
                  <Image src="/toudi-niuma.svg" alt="投递牛马" width={34} height={34} priority />
                </span>
                <span className="text-xl font-bold">AI-JobPilot-Cloud</span>
              </Link>
              <h2 className="mt-16 text-3xl font-bold leading-tight">求职资料、岗位与投递进度，由你自己的账号安全隔离。</h2>
              <p className="mt-5 text-sm leading-7 text-blue-100">服务器不保存招聘平台密码或 Cookie。招聘平台登录始终留在你自己的浏览器中。</p>
            </div>
            <p className="text-xs text-blue-200">第 3 轮用户系统 · 开发试用版</p>
          </section>

          <section className="p-6 sm:p-10 md:p-12">
            <div className="mx-auto max-w-md">
              <div className="mb-8 md:hidden">
                <Link href="/" className="inline-flex items-center gap-2 font-bold text-slate-900 dark:text-white">
                  <Image src="/toudi-niuma.svg" alt="投递牛马" width={32} height={32} priority />
                  AI-JobPilot-Cloud
                </Link>
              </div>
              <h1 className="text-3xl font-bold tracking-tight text-slate-950 dark:text-white">{title}</h1>
              <p className="mt-2 text-sm leading-6 text-slate-500 dark:text-slate-400">{description}</p>
              <div className="mt-8">{children}</div>
              <div className="mt-7 text-center text-sm text-slate-500 dark:text-slate-400">{footer}</div>
            </div>
          </section>
        </div>
      </div>
    </main>
  )
}
