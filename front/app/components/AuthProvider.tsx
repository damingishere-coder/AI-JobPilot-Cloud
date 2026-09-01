"use client"

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react"
import { AuthApiError, cloudApiRequest } from "@/lib/authApi"

export type AuthUser = {
  id: string
  emailMasked: string
  status: "ACTIVE" | "LOCKED" | "DISABLED" | "PENDING" | "DELETION_PENDING" | "DELETED"
  role: "USER" | "ADMIN"
}

export type UserProfile = {
  displayName: string | null
  city: string | null
  timezone: string
  locale: string
}

type AuthPayload = {
  user: AuthUser
  session: { expiresAt: string }
  csrfToken: string
}

type MePayload = AuthUser & {
  profile: UserProfile
  quotaSummary: unknown[]
  sessionExpiresAt: string
  csrfToken: string
}

export type RegistrationResult = {
  verificationRequired: boolean
  emailMasked?: string
}

type EmailActionPayload = {
  accepted: boolean
  verificationRequired: boolean
  emailMasked: string
}

type AuthContextValue = {
  enabled: boolean
  loading: boolean
  user: AuthUser | null
  login: (email: string, password: string, rememberMe: boolean) => Promise<void>
  register: (
    email: string,
    password: string,
    inviteCode: string,
    acceptTerms: boolean,
    acceptPrivacy: boolean,
    acceptAiDisclosure: boolean,
  ) => Promise<RegistrationResult>
  deleteAccount: (password: string, confirmation: string) => Promise<void>
  logout: () => Promise<void>
  refresh: () => Promise<AuthUser | null>
  secureRequest: <T>(path: string, init?: RequestInit) => Promise<T>
}

const AuthContext = createContext<AuthContextValue | null>(null)
const cloudAuthEnabled = process.env.CLOUD_LOGIN_REQUIRED !== "false"

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [loading, setLoading] = useState(cloudAuthEnabled)
  const [user, setUser] = useState<AuthUser | null>(null)
  const csrfRef = useRef<string | null>(null)

  const fetchCsrf = useCallback(async () => {
    const data = await cloudApiRequest<{ csrfToken: string }>("/api/auth/csrf")
    csrfRef.current = data.csrfToken
    return data.csrfToken
  }, [])

  const refresh = useCallback(async () => {
    if (!cloudAuthEnabled) {
      setLoading(false)
      return null
    }
    try {
      // /api/me is a CSRF-protected POST: fetch the session token first, then
      // carry it on the request so Spring Security's CsrfFilter validates it.
      const csrfToken = await fetchCsrf()
      const data = await cloudApiRequest<MePayload>(
        "/api/me",
        { method: "POST" },
        csrfToken,
      )
      csrfRef.current = data.csrfToken
      const currentUser: AuthUser = {
        id: data.id,
        emailMasked: data.emailMasked,
        role: data.role,
        status: data.status,
      }
      setUser(currentUser)
      return currentUser
    } catch (error) {
      if (error instanceof AuthApiError && (error.status === 401 || error.status === 403)) {
        csrfRef.current = null
        setUser(null)
        return null
      }
      setUser(null)
      throw error
    } finally {
      setLoading(false)
    }
  }, [fetchCsrf])

  useEffect(() => {
    if (!cloudAuthEnabled) {
      setLoading(false)
      return
    }
    void refresh().catch(() => undefined)
  }, [refresh])

  const login = useCallback(async (email: string, password: string, rememberMe: boolean) => {
    const csrfToken = await fetchCsrf()
    const data = await cloudApiRequest<AuthPayload>(
      "/api/auth/login",
      {
        method: "POST",
        body: JSON.stringify({ email, password, rememberMe }),
      },
      csrfToken,
    )
    csrfRef.current = data.csrfToken
    setUser(data.user)
  }, [fetchCsrf])

  const register = useCallback(async (
    email: string,
    password: string,
    inviteCode: string,
    acceptTerms: boolean,
    acceptPrivacy: boolean,
    acceptAiDisclosure: boolean,
  ) => {
    const csrfToken = await fetchCsrf()
    const data = await cloudApiRequest<AuthPayload | EmailActionPayload>(
      "/api/auth/register",
      {
        method: "POST",
        body: JSON.stringify({
          email,
          password,
          inviteCode,
          acceptTerms,
          acceptPrivacy,
          acceptAiDisclosure,
        }),
      },
      csrfToken,
    )
    if ("verificationRequired" in data) {
      return { verificationRequired: data.verificationRequired, emailMasked: data.emailMasked }
    }
    csrfRef.current = data.csrfToken
    setUser(data.user)
    return { verificationRequired: false }
  }, [fetchCsrf])

  const secureRequest = useCallback(async <T,>(path: string, init: RequestInit = {}) => {
    const method = (init.method ?? "GET").toUpperCase()
    const mutation = !["GET", "HEAD", "OPTIONS"].includes(method)
    const execute = async () => cloudApiRequest<T>(
      path,
      init,
      mutation ? (csrfRef.current ?? await fetchCsrf()) : undefined,
    )
    let retriedCsrf = false
    for (;;) {
      try {
        return await execute()
      } catch (error) {
        if (
          mutation &&
          !retriedCsrf &&
          error instanceof AuthApiError &&
          error.code === "CSRF_INVALID"
        ) {
          retriedCsrf = true
          await fetchCsrf()
          continue
        }
        if (
          error instanceof AuthApiError &&
          (error.code === "AUTH_REQUIRED" || error.code === "ACCOUNT_DISABLED")
        ) {
          csrfRef.current = null
          setUser(null)
        }
        throw error
      }
    }
  }, [fetchCsrf])

  const logout = useCallback(async () => {
    try {
      await secureRequest<{ loggedOut: boolean }>("/api/auth/logout", { method: "POST" })
    } finally {
      csrfRef.current = null
      setUser(null)
    }
  }, [secureRequest])

  const deleteAccount = useCallback(async (password: string, confirmation: string) => {
    await secureRequest("/api/account/deletion", {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: JSON.stringify({ password, confirmation }),
    })
    csrfRef.current = null
    setUser(null)
  }, [secureRequest])

  const value = useMemo<AuthContextValue>(() => ({
    enabled: cloudAuthEnabled,
    loading,
    user,
    login,
    register,
    deleteAccount,
    logout,
    refresh,
    secureRequest,
  }), [loading, user, login, register, deleteAccount, logout, refresh, secureRequest])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error("useAuth 必须在 AuthProvider 内使用")
  }
  return context
}
