"use client"

import { FormEvent, useCallback, useEffect, useState } from "react"
import { BiCog, BiLoaderAlt, BiRefresh, BiSave } from "react-icons/bi"
import PageHeader from "@/app/components/PageHeader"
import { useAuth } from "@/app/components/AuthProvider"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { AuthApiError } from "@/lib/authApi"

type Preference = {
  id: string
  version: number
  targetTitles: string[]
  cities: string[]
  salaryMinK: number | null
  salaryMaxK: number | null
  experienceLevels: string[]
  degreeLevels: string[]
  industries: string[]
  companyScales: string[]
  preferredCompanies: string[]
  excludedCompanies: string[]
  excludedKeywords: string[]
  extraFilters: Record<string, unknown>
  updatedAt: string
}

type FormState = {
  version: number | null
  targetTitles: string
  cities: string
  salaryMinK: string
  salaryMaxK: string
  experienceLevels: string
  degreeLevels: string
  industries: string
  companyScales: string
  preferredCompanies: string
  excludedCompanies: string
  excludedKeywords: string
}

const emptyForm: FormState = {
  version: null,
  targetTitles: "",
  cities: "",
  salaryMinK: "",
  salaryMaxK: "",
  experienceLevels: "",
  degreeLevels: "",
  industries: "",
  companyScales: "",
  preferredCompanies: "",
  excludedCompanies: "",
  excludedKeywords: "",
}

function lines(value: string) {
  return value.split(/[，,\n]/).map((item) => item.trim()).filter(Boolean)
}

function joined(values: string[]) {
  return values.join("\n")
}

function formFromPreference(value: Preference | null): FormState {
  if (!value) return emptyForm
  return {
    version: value.version,
    targetTitles: joined(value.targetTitles),
    cities: joined(value.cities),
    salaryMinK: value.salaryMinK?.toString() ?? "",
    salaryMaxK: value.salaryMaxK?.toString() ?? "",
    experienceLevels: joined(value.experienceLevels),
    degreeLevels: joined(value.degreeLevels),
    industries: joined(value.industries),
    companyScales: joined(value.companyScales),
    preferredCompanies: joined(value.preferredCompanies),
    excludedCompanies: joined(value.excludedCompanies),
    excludedKeywords: joined(value.excludedKeywords),
  }
}

function MultiValueField({ id, label, value, onChange, hint }: { id: string; label: string; value: string; onChange: (value: string) => void; hint?: string }) {
  return (
    <div>
      <label htmlFor={id} className="mb-2 block text-sm font-semibold text-slate-700">{label}</label>
      <Textarea id={id} value={value} onChange={(event) => onChange(event.target.value)} placeholder="每行一项，也可以使用逗号分隔" className="min-h-24" />
      {hint && <p className="mt-1 text-xs text-slate-400">{hint}</p>}
    </div>
  )
}

export default function PreferencesPage() {
  const { secureRequest } = useAuth()
  const [form, setForm] = useState<FormState>(emptyForm)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState("")
  const [message, setMessage] = useState("")

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const current = await secureRequest<Preference | null>("/api/preferences")
      setForm(formFromPreference(current))
      setError("")
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "求职目标加载失败")
    } finally {
      setLoading(false)
    }
  }, [secureRequest])

  useEffect(() => {
    void load()
  }, [load])

  const set = (field: keyof FormState, value: string) => setForm((current) => ({ ...current, [field]: value }))

  const save = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError("")
    setMessage("")
    try {
      const updated = await secureRequest<Preference>("/api/preferences", {
        method: "PUT",
        body: JSON.stringify({
          version: form.version,
          targetTitles: lines(form.targetTitles),
          cities: lines(form.cities),
          salaryMinK: form.salaryMinK === "" ? null : Number(form.salaryMinK),
          salaryMaxK: form.salaryMaxK === "" ? null : Number(form.salaryMaxK),
          experienceLevels: lines(form.experienceLevels),
          degreeLevels: lines(form.degreeLevels),
          industries: lines(form.industries),
          companyScales: lines(form.companyScales),
          preferredCompanies: lines(form.preferredCompanies),
          excludedCompanies: lines(form.excludedCompanies),
          excludedKeywords: lines(form.excludedKeywords),
          extraFilters: {},
        }),
      })
      setForm(formFromPreference(updated))
      setMessage(`已保存为第 ${updated.version} 版`)
    } catch (requestError) {
      if (requestError instanceof AuthApiError && requestError.code === "RESOURCE_VERSION_CONFLICT") {
        await load()
        setError("其他页面已经更新了求职目标，已重新加载最新版本，请确认后再次保存。")
      } else if (requestError instanceof AuthApiError && requestError.fieldErrors.length > 0) {
        setError(requestError.fieldErrors.map((item) => item.reason).join("；"))
      } else {
        setError(requestError instanceof Error ? requestError.message : "求职目标保存失败")
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        icon={<BiCog size={28} />}
        title="求职目标"
        subtitle="这些条件会作为后续岗位筛选和 AI 匹配的可信输入"
        iconClass="text-cyan-600"
        accentBgClass="bg-cyan-50 dark:bg-cyan-500/15"
        actions={<Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}><BiRefresh />刷新</Button>}
      />

      {error && <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
      {message && <div role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">{message}</div>}

      <Card>
        <CardHeader>
          <CardTitle>当前配置{form.version ? ` · 第 ${form.version} 版` : ""}</CardTitle>
          <CardDescription>保存时会创建新版本，避免旧的岗位分析结果失去依据。</CardDescription>
        </CardHeader>
        <CardContent>
          {loading ? (
            <div className="flex items-center gap-2 py-16 text-sm text-slate-500"><BiLoaderAlt className="animate-spin" />正在读取求职目标…</div>
          ) : (
            <form onSubmit={save} className="space-y-6">
              <div className="grid gap-5 lg:grid-cols-2">
                <MultiValueField id="target-titles" label="目标职位 *" value={form.targetTitles} onChange={(value) => set("targetTitles", value)} hint="至少 1 项，最多 10 项" />
                <MultiValueField id="cities" label="目标城市" value={form.cities} onChange={(value) => set("cities", value)} hint="不填写表示不限城市" />
              </div>

              <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-4">
                <div><label htmlFor="salary-min" className="mb-2 block text-sm font-semibold text-slate-700">最低月薪（K）</label><Input id="salary-min" type="number" min="0" max="1000" step="0.01" value={form.salaryMinK} onChange={(event) => set("salaryMinK", event.target.value)} /></div>
                <div><label htmlFor="salary-max" className="mb-2 block text-sm font-semibold text-slate-700">最高月薪（K）</label><Input id="salary-max" type="number" min="0" max="1000" step="0.01" value={form.salaryMaxK} onChange={(event) => set("salaryMaxK", event.target.value)} /></div>
                <MultiValueField id="experience" label="经验要求" value={form.experienceLevels} onChange={(value) => set("experienceLevels", value)} />
                <MultiValueField id="degree" label="学历要求" value={form.degreeLevels} onChange={(value) => set("degreeLevels", value)} />
              </div>

              <div className="grid gap-5 lg:grid-cols-2">
                <MultiValueField id="industries" label="偏好行业" value={form.industries} onChange={(value) => set("industries", value)} />
                <MultiValueField id="scales" label="公司规模" value={form.companyScales} onChange={(value) => set("companyScales", value)} />
                <MultiValueField id="preferred-companies" label="优先公司" value={form.preferredCompanies} onChange={(value) => set("preferredCompanies", value)} />
                <MultiValueField id="excluded-companies" label="排除公司" value={form.excludedCompanies} onChange={(value) => set("excludedCompanies", value)} />
              </div>

              <MultiValueField id="excluded-keywords" label="排除关键词" value={form.excludedKeywords} onChange={(value) => set("excludedKeywords", value)} hint="岗位标题或描述中出现这些词时，后续匹配流程会重点提示" />

              <div className="flex justify-end">
                <Button type="submit" disabled={saving || lines(form.targetTitles).length === 0}>
                  {saving ? <BiLoaderAlt className="animate-spin" /> : <BiSave />}
                  {saving ? "正在保存…" : "保存求职目标"}
                </Button>
              </div>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
