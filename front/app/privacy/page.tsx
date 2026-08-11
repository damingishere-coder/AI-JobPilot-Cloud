import Link from "next/link"

export default function PrivacyPage() {
  return (
    <main className="min-h-screen bg-slate-50 px-4 py-12 text-slate-800">
      <article className="mx-auto max-w-3xl rounded-2xl border border-slate-200 bg-white p-8 shadow-sm sm:p-12">
        <p className="text-sm font-semibold text-blue-600">开发试用版 · 2026-08</p>
        <h1 className="mt-2 text-3xl font-bold">隐私与安全说明</h1>
        <div className="mt-8 space-y-6 text-sm leading-7 text-slate-600">
          <section><h2 className="font-semibold text-slate-900">本轮保存的数据</h2><p>系统保存标准化邮箱、不可逆密码哈希、基础资料、账号状态和脱敏安全审计。不同用户的数据通过服务端身份与 PostgreSQL 行级安全隔离。</p></section>
          <section><h2 className="font-semibold text-slate-900">明确不保存</h2><p>Cloud 服务器不接收或保存招聘平台密码、Cookie、LocalStorage、SessionStorage 或浏览器 Profile。招聘平台登录保留在用户自己的浏览器中。</p></section>
          <section><h2 className="font-semibold text-slate-900">会话安全</h2><p>Web 会话保存在 Redis，浏览器只持有 HttpOnly Session Cookie。退出后当前会话立即失效；日志不会记录密码、Session 或 CSRF Token。</p></section>
          <p>本说明需在正式上线前结合实际部署地区、数据保留期、删除流程和联系方式完成法律审核。</p>
        </div>
        <Link href="/register" className="mt-10 inline-flex font-semibold text-blue-600 hover:underline">返回注册</Link>
      </article>
    </main>
  )
}
