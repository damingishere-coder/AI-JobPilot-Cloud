import Link from "next/link"

export default function AiDisclosurePage() {
  return <main className="min-h-screen bg-slate-50 px-4 py-12 text-slate-800">
    <article className="mx-auto max-w-3xl rounded-2xl border border-amber-200 bg-white p-8 shadow-sm sm:p-12">
      <p className="text-sm font-semibold text-amber-700">法律终稿待提供 · 当前不可用于正式上线</p>
      <h1 className="mt-2 text-3xl font-bold">第三方 AI 数据处理说明</h1>
      <p className="mt-8 text-sm leading-7 text-slate-600">本页仅是集成占位，不构成正式条款。律师终稿到位后将原样替换，并以正式生效日期作为版本号。系统只向配置的 DeepSeek 官方接口发送岗位匹配所必需且已经脱敏的数据。</p>
      <Link href="/register" className="mt-10 inline-flex font-semibold text-blue-600 hover:underline">返回注册</Link>
    </article>
  </main>
}
