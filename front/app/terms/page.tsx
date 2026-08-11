import Link from "next/link"

export default function TermsPage() {
  return (
    <main className="min-h-screen bg-slate-50 px-4 py-12 text-slate-800">
      <article className="mx-auto max-w-3xl rounded-2xl border border-slate-200 bg-white p-8 shadow-sm sm:p-12">
        <p className="text-sm font-semibold text-blue-600">开发试用版 · 2026-08</p>
        <h1 className="mt-2 text-3xl font-bold">试用版服务条款</h1>
        <div className="mt-8 space-y-6 text-sm leading-7 text-slate-600">
          <p>本页面是第 3 轮开发测试说明，不是正式商业服务条款。公开运营或收费前，必须由项目所有者完成法律审核并更新版本。</p>
          <section><h2 className="font-semibold text-slate-900">使用边界</h2><p>Cloud 用于管理用户自己的求职资料和工作流。用户应遵守适用法律、招聘平台规则及合理访问频率，不得用于绕过验证码、风控或未经授权的数据访问。</p></section>
          <section><h2 className="font-semibold text-slate-900">账号责任</h2><p>请使用独立强密码并妥善保护登录会话。发现异常访问时应立即退出并联系项目维护者处理。</p></section>
          <section><h2 className="font-semibold text-slate-900">试用状态</h2><p>当前版本仍处于开发迁移阶段，不承诺生产可用性。重要数据应保留独立备份。</p></section>
        </div>
        <Link href="/register" className="mt-10 inline-flex font-semibold text-blue-600 hover:underline">返回注册</Link>
      </article>
    </main>
  )
}
