"use client";

import { FormEvent, Suspense, useState } from "react";
import Link from "next/link";
import AuthFrame from "../components/common/AuthFrame";
import { useRouter, useSearchParams } from "next/navigation";
import { login } from "../lib/api/userApi";
import { getNextPath } from "../lib/authNavigation";


export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const nextPath = getNextPath(searchParams.get("next"));

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleLogin = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      await login({ email, password });
      router.replace(nextPath);
    } catch (caughtError) {
      console.error(caughtError);
      setError(
        caughtError instanceof Error
          ? caughtError.message
          : "로그인에 실패했습니다.",
      );
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthFrame>
        <div>
          <div className="mb-7">
            <h2 className="text-3xl font-bold tracking-tight text-zinc-900">
              로그인
            </h2>

            <p className="mt-2 text-sm text-zinc-500">
              Budzet에 오신 것을 환영합니다.
            </p>
          </div>

          {searchParams.get("registered") === "1" && <p role="status" className="mb-5 rounded-xl bg-emerald-50 px-4 py-3 text-sm text-emerald-700">가입이 완료됐어요. 로그인해 시작하세요.</p>}
          <form onSubmit={handleLogin} className="space-y-5">

            {/* 이메일 */}
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-zinc-700 mb-2">
                이메일
              </label>

              <input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="이메일을 입력해주세요."
                autoComplete="email"
                className="w-full h-12 rounded-xl border border-zinc-300 px-4 text-sm outline-none transition focus:border-zinc-900 focus:ring-2 focus:ring-zinc-900/10"
                required
              />
            </div>

            {/* 비밀번호 */}
            <div>
              <label htmlFor="password" className="block text-sm font-medium text-zinc-700 mb-2">
                비밀번호
              </label>

              <div className="relative"><input
                id="password"
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="비밀번호를 입력해주세요."
                autoComplete="current-password"
                className="w-full h-12 rounded-xl border border-zinc-300 pl-4 pr-16 text-sm outline-none transition focus:border-zinc-900 focus:ring-2 focus:ring-zinc-900/10"
                required
              /><button type="button" aria-label={showPassword ? "비밀번호 숨기기" : "비밀번호 표시"} aria-pressed={showPassword} onClick={() => setShowPassword(current => !current)} className="absolute inset-y-0 right-3 my-auto h-9 rounded-lg px-2 text-xs font-semibold text-zinc-500 hover:text-indigo-600">{showPassword ? "숨김" : "표시"}</button></div>
            </div>

            {/* 에러 메시지 */}
            {error && (
              <p role="alert" className="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">
                {error}
              </p>
            )}

            {/* 로그인 버튼 */}
            <button
              type="submit"
              disabled={loading}
              className="w-full h-12 rounded-xl bg-zinc-900 text-white font-semibold transition hover:bg-zinc-800 disabled:bg-zinc-400"
            >
              {loading ? "로그인 중..." : "로그인"}
            </button>
          </form>

          {/* 회원가입 이동 */}
          <div className="mt-6 pt-6 border-t border-zinc-100 text-center">
            <span className="text-sm text-zinc-500">
              아직 계정이 없으신가요?
            </span>

            <Link
              href={{ pathname: "/signup", query: { next: nextPath } }}
              className="ml-2 text-sm font-semibold text-zinc-900 hover:underline"
            >
              회원가입
            </Link>
          </div>
        </div>
    </AuthFrame>
  );
}
