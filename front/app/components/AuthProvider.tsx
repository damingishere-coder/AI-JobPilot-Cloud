"use client"

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react"
import { AuthApiError, cloudApiRequest } from "@/lib/authApi"

export type AuthUser = {
  id: string
  emailMasked: string
  status: "ACTIVE" | "LOCKED" | "DISABLED" | "PENDING"
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

type AuthContextValue = {
  enabled: boolean
  loading: boolean
  user: AuthUser | null
  login: (email: string, password: string, rememberMe: boolean) => Promise<void>
  register: (email: string, password: string, acceptTerms: boolean) => Promise<void>
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
      const data = await cloudApiRequest<MePayload>("/api/me")
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
  }, [])

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

  const register = useCallback(async (email: string, password: string, acceptTerms: boolean) => {
    const csrfToken = await fetchCsrf()
    const data = await cloudApiRequest<AuthPayload>(
      "/api/auth/register",
      {
        method: "POST",
        body: JSON.stringify({ email, password, acceptTerms }),
      },
      csrfToken,
    )
    csrfRef.current = data.csrfToken
    setUser(data.user)
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
        if (error instanceof AuthApiError && error.status === 401) {
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

  const value = useMemo<AuthContextValue>(() => ({
    enabled: cloudAuthEnabled,
    loading,
    user,
    login,
    register,
    logout,
    refresh,
    secureRequest,
  }), [loading, user, login, register, logout, refresh, secureRequest])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error("useAuth 必须在 AuthProvider 内使用")
  }
  return context
}
