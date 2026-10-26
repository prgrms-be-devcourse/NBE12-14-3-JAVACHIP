"use client";

import { FormEvent, Suspense, useState } from "react";
import Link from "next/link";
import AuthFrame from "../components/common/AuthFrame";
import { useRouter, useSearchParams } from "next/navigation";
import { signup } from "../lib/api/userApi";
import { getNextPath } from "../lib/authNavigation";

export default function SignupPage() {
  return <Suspense fallback={null}><SignupForm /></Suspense>;
}

function SignupForm() {
  const searchParams = useSearchParams();
  const nextPath = getNextPath(searchParams.get("next"));
  const router = useRouter();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [name, setName] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const handleSignup = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setError("");
    setLoading(true);

    try {
      await signup({ email, password, name });
      router.replace("/login?" + new URLSearchParams({ registered: "1", next: nextPath }).toString());
    } catch (caughtError) {
      console.error(caughtError);
      setError(
        caughtError instanceof Error
          ? caughtError.message
          : "회원가입에 실패했습니다.",
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
              회원가입
            </h2>
            <p className="mt-2 text-sm text-zinc-500">
              Budzet 서비스를 시작해보세요.
            </p>
          </div>

          <form onSubmit={handleSignup} className="space-y-5">
            {/* 이름 */}
            <div>
              <label htmlFor="name" className="block text-sm font-medium text-zinc-700 mb-2">
                이름
              </label>

              <input
                id="name"
                maxLength={50}
                autoComplete="name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="이름을 입력해주세요."
                className="w-full h-12 rounded-xl border border-zinc-300 px-4 text-sm outline-none transition focus:border-zinc-900 focus:ring-2 focus:ring-zinc-900/10"
                required
              />
            </div>

            {/* 이메일 */}
            <div>
              <label htmlFor="email" className="block text-sm font-medium text-zinc-700 mb-2">
                이메일
              </label>

              <input
                id="email"
                autoComplete="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="이메일을 입력해주세요."
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
                minLength={8}
                maxLength={20}
                autoComplete="new-password"
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="8~20자로 입력해주세요."
                className="w-full h-12 rounded-xl border border-zinc-300 pl-4 pr-16 text-sm outline-none transition focus:border-zinc-900 focus:ring-2 focus:ring-zinc-900/10"
                required
              /><button type="button" aria-label={showPassword ? "비밀번호 숨기기" : "비밀번호 표시"} aria-pressed={showPassword} onClick={() => setShowPassword(current => !current)} className="absolute inset-y-0 right-3 my-auto h-9 rounded-lg px-2 text-xs font-semibold text-zinc-500 hover:text-indigo-600">{showPassword ? "숨김" : "표시"}</button></div>
            </div>

            {/* 에러 */}
            {error && (
              <p role="alert" className="text-sm text-red-600 bg-red-50 rounded-lg px-3 py-2">
                {error}
              </p>
            )}

            {/* 버튼 */}
            <button
              type="submit"
              disabled={loading}
              className="w-full h-12 rounded-xl bg-zinc-900 text-white font-semibold transition hover:bg-zinc-800 disabled:bg-zinc-400"
            >
              {loading ? "가입 중..." : "회원가입"}
            </button>
          </form>

          {/* 로그인 이동 */}
          <div className="mt-6 pt-6 border-t border-zinc-100 text-center">
            <span className="text-sm text-zinc-500">
              이미 계정이 있으신가요?
            </span>

            <Link
              href={{ pathname: "/login", query: { next: nextPath } }}
              className="ml-2 text-sm font-semibold text-zinc-900 hover:underline"
            >
              로그인
            </Link>
          </div>
        </div>
    </AuthFrame>
  );
}
