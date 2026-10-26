"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { getMe } from "@/app/lib/api/userApi";
import { getMemberAuthority } from "@/app/lib/api/memberApi";

import { ApiError } from "@/app/lib/api/types";

import {
    getBudget,
    type Budget,
} from "@/app/lib/api/budgetApi";

import {
    getBudgetChange,
    type BudgetChangeDetailResponse,
} from "@/app/lib/api/budgetChangeApi";

function formatMoney(amount: number, currency: "KRW" | "USD" | "JPY") {
    const symbols = { KRW: "원", USD: "$", JPY: "¥" };

    const locales = { KRW: "ko-KR", USD: "en-US", JPY: "ja-JP" };
    const locale = locales[currency] || "ko-KR";

    const formattedAmount = amount.toLocaleString(locale);

    return currency === "KRW"
        ? `${formattedAmount}${symbols[currency]}`
        : `${symbols[currency]}${formattedAmount}`;
}

function formatDateTime(
    date: string | null | undefined,
) {
    if (!date) {
        return "-";
    }

    const value = new Date(date);

    if (Number.isNaN(value.getTime())) {
        return "-";
    }

    return value.toLocaleString("ko-KR", {
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    });
}

function formatBudgetType(type: string) {
    switch (type) {
        case "SETTLEMENT":
            return "정산";

        case "INCREASE":
            return "예산 증가";

        case "DECREASE":
            return "예산 감소";

        default:
            return type;
    }
}

function getErrorMessage(error: unknown) {
    if (
        error instanceof ApiError &&
        error.status === 401
    ) {
        return null;
    }

    return error instanceof ApiError
        ? error.message
        : "정산 상세 정보를 불러오지 못했습니다.";
}

export default function SettlementDetailPage() {
    const params = useParams();

    const roomId = Number(params.roomId);
    const changeId = Number(params.changeId);

    const [change, setChange] =
        useState<BudgetChangeDetailResponse | null>(
            null,
        );

    const [budget, setBudget] =
        useState<Budget | null>(null);

    const [authority, setAuthority] = useState<
        "OWNER" | "OPERATOR" | "MEMBER" | null
    >(null);

    const [loading, setLoading] =
        useState(true);

    const [error, setError] =
        useState<string | null>(null);

    const loadDetail = async () => {
        if (
            !Number.isFinite(roomId) ||
            roomId <= 0 ||
            !Number.isFinite(changeId) ||
            changeId <= 0
        ) {
            setError(
                "올바른 정산 정보가 없습니다.",
            );
            setLoading(false);
            return;
        }

        setLoading(true);
        setError(null);

        try {
            const [
                changeData,
                budgetData,
                me,
            ] = await Promise.all([
                getBudgetChange(
                    roomId,
                    changeId,
                ),
                getBudget(roomId),
                getMe(),
            ]);

            const authorityData = await getMemberAuthority(
                roomId,
                me.id,
            );

            setChange(changeData);
            setBudget(budgetData);
            setAuthority(
                authorityData as "OWNER" | "OPERATOR" | "MEMBER",
            );
        } catch (caughtError) {
            setError(
                getErrorMessage(caughtError),
            );
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        let cancelled = false;

        const load = async () => {
            if (
                !Number.isFinite(roomId) ||
                roomId <= 0 ||
                !Number.isFinite(changeId) ||
                changeId <= 0
            ) {
                setError(
                    "올바른 정산 정보가 없습니다.",
                );
                setLoading(false);
                return;
            }

            setLoading(true);
            setError(null);

            try {
                const [
                    changeData,
                    budgetData,
                    me,
                ] = await Promise.all([
                    getBudgetChange(
                        roomId,
                        changeId,
                    ),
                    getBudget(roomId),
                    getMe(),
                ]);

                const authorityData = await getMemberAuthority(
                    roomId,
                    me.id,
                );

                if (cancelled) {
                    return;
                }

                setChange(changeData);
                setBudget(budgetData);
                setAuthority(
                    authorityData as "OWNER" | "OPERATOR" | "MEMBER",
                );
            } catch (caughtError) {
                if (!cancelled) {
                    setError(
                        getErrorMessage(
                            caughtError,
                        ),
                    );
                }
            } finally {
                if (!cancelled) {
                    setLoading(false);
                }
            }
        };

        load();

        return () => {
            cancelled = true;
        };
    }, [roomId, changeId]);

    const currency: Budget["currency"] =
        budget?.currency ?? "KRW";

    const approvedAmount =
        change?.changeBudget ?? 0;

    const spentAmount =
        change?.changedBudget ?? 0;

    /*
     * 승인 금액 - 실제 지출 금액
     *
     * 양수: 반환 금액
     * 음수: 추가 지출
     */
    const balanceAmount =
        approvedAmount - spentAmount;

    const refundAmount = Math.max(
        balanceAmount,
        0,
    );

    const additionalExpense = Math.max(
        -balanceAmount,
        0,
    );

    return (
        <main className="min-h-screen bg-[#f8f8fb] text-zinc-900">
            <div className="mx-auto max-w-5xl px-5 py-10 sm:px-8">
                {/* =========================
                    상단 영역
                    아래 상세 카드와 동일한 2열 구조
                ========================== */}
                <div className="grid gap-6 lg:grid-cols-[minmax(0,1.55fr)_minmax(280px,1fr)]">
                    {/* 제목 영역 */}
                    <div>
                        <p className="text-sm font-medium text-indigo-600">
                            정산 내역 / 상세보기
                        </p>

                        <h1 className="mt-1 text-3xl font-bold tracking-tight text-zinc-900">
                            정산 상세
                        </h1>

                        <p className="mt-2 text-sm text-zinc-500">
                            처리된 예산 내역과 정산 정보를
                            확인할 수 있습니다.
                        </p>
                    </div>

                    {/* =========================
                        목록 / 수정 버튼
                        오른쪽 카드 영역 안에만 위치
                    ========================== */}
                    <div className="flex items-start justify-end gap-2 lg:pt-1">
                        <Link
                            href={`/rooms/${roomId}/settlements`}
                            className="inline-flex items-center justify-center rounded-lg border border-zinc-200 bg-white px-3.5 py-2 text-sm font-semibold text-zinc-600 shadow-sm transition-colors hover:border-zinc-300 hover:bg-zinc-50 hover:text-zinc-800"
                        >
                            목록으로
                        </Link>
                        {authority === "OWNER" && (
                            <Link
                                href={`/rooms/${roomId}/settlements/${changeId}/edit`}
                                className="inline-flex items-center justify-center rounded-lg bg-indigo-600 px-3.5 py-2 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-700"
                            >
                                수정
                            </Link>
                        )}
                    </div>
                </div>

                {/* =========================
                    로딩
                ========================== */}
                {loading && (
                    <div className="mt-8 rounded-2xl border border-zinc-200 bg-white px-6 py-16 text-center text-sm text-zinc-500 shadow-sm">
                        정산 상세 정보를
                        불러오는 중...
                    </div>
                )}

                {/* =========================
                    에러
                ========================== */}
                {!loading && error && (
                    <div className="mt-8 rounded-2xl border border-red-200 bg-red-50 px-6 py-10 text-center">
                        <p className="text-sm font-medium text-red-600">
                            {error}
                        </p>

                        <button
                            type="button"
                            onClick={loadDetail}
                            className="mt-4 rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-semibold text-red-600 transition-colors hover:bg-red-50"
                        >
                            다시 시도
                        </button>
                    </div>
                )}

                {/* =========================
                    데이터 없음
                ========================== */}
                {!loading &&
                    !error &&
                    !change && (
                        <div className="mt-8 rounded-2xl border border-dashed border-zinc-300 bg-white px-6 py-16 text-center">
                            <p className="text-sm text-zinc-500">
                                해당 정산 내역을
                                찾을 수 없습니다.
                            </p>
                        </div>
                    )}

                {/* =========================
                    상세 내용
                ========================== */}
                {!loading &&
                    !error &&
                    change && (
                        <div className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,1.55fr)_minmax(280px,1fr)]">
                            {/* =========================
                                왼쪽 영역
                            ========================== */}
                            <div className="space-y-6">
                                {/* =========================
                                    정산 금액
                                ========================== */}
                                <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6">
                                    <div className="flex items-center justify-between gap-4">
                                        <div>
                                            <h2 className="text-lg font-bold text-zinc-800">
                                                정산 금액
                                            </h2>

                                            <p className="mt-1 text-sm text-zinc-500">
                                                승인된 예산과 실제 지출 금액을
                                                확인하세요.
                                            </p>
                                        </div>

                                        <span className="shrink-0 rounded-full bg-emerald-50 px-3 py-1.5 text-xs font-bold text-emerald-600">
                                            {formatBudgetType(
                                                change.budgetType,
                                            )}
                                        </span>
                                    </div>

                                    <div className="mt-5 grid gap-3 sm:grid-cols-3">
                                        {/* 승인 금액 */}
                                        <div className="rounded-xl bg-indigo-50 px-4 py-4">
                                            <p className="text-xs font-semibold text-indigo-500">
                                                승인 금액
                                            </p>

                                            <p className="mt-1.5 text-lg font-extrabold text-indigo-700">
                                                {formatMoney(
                                                    approvedAmount,
                                                    currency,
                                                )}
                                            </p>
                                        </div>

                                        {/* 지출 금액 */}
                                        <div className="rounded-xl bg-zinc-50 px-4 py-4">
                                            <p className="text-xs font-semibold text-zinc-500">
                                                지출 금액
                                            </p>

                                            <p className="mt-1.5 text-lg font-extrabold text-zinc-900">
                                                {formatMoney(
                                                    spentAmount,
                                                    currency,
                                                )}
                                            </p>
                                        </div>

                                        {/* 반환 금액 / 추가 지출 */}
                                        {additionalExpense >
                                        0 ? (
                                            <div className="rounded-xl bg-red-50 px-4 py-4">
                                                <p className="text-xs font-semibold text-red-600">
                                                    추가 지출
                                                </p>

                                                <p className="mt-1.5 text-lg font-extrabold text-red-700">
                                                    {formatMoney(
                                                        additionalExpense,
                                                        currency,
                                                    )}
                                                </p>
                                            </div>
                                        ) : (
                                            <div className="rounded-xl bg-emerald-50 px-4 py-4">
                                                <p className="text-xs font-semibold text-emerald-600">
                                                    반환 금액
                                                </p>

                                                <p className="mt-1.5 text-lg font-extrabold text-emerald-700">
                                                    {formatMoney(
                                                        refundAmount,
                                                        currency,
                                                    )}
                                                </p>
                                            </div>
                                        )}
                                    </div>
                                </article>

                                {/* =========================
                                    지출 내용
                                ========================== */}
                                <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6">
                                    <h2 className="text-lg font-bold text-zinc-800">
                                        지출 내용
                                    </h2>

                                    <div className="mt-4 rounded-xl bg-zinc-50 px-4 py-4">
                                        <p className="whitespace-pre-wrap text-sm leading-6 text-zinc-700">
                                            {change.reason ||
                                                "입력된 지출 내용이 없습니다."}
                                        </p>
                                    </div>
                                </article>

                                {/* =========================
                                    예산 신청 정보
                                ========================== */}
                                <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6">
                                    <h2 className="text-lg font-bold text-zinc-800">
                                        예산 신청 정보
                                    </h2>

                                    {change.requestId !=
                                    null ? (
                                        <div className="mt-4 space-y-3">
                                            {/* 신청자 */}
                                            <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                                <span className="text-sm font-medium text-zinc-500">
                                                    신청자
                                                </span>

                                                <span className="text-right text-sm font-bold text-zinc-800">
                                                    {change.userName ||
                                                        "-"}
                                                </span>
                                            </div>

                                            {/* 이메일 */}
                                            <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                                <span className="text-sm font-medium text-zinc-500">
                                                    이메일
                                                </span>

                                                <span className="break-all text-right text-sm font-semibold text-zinc-700">
                                                    {change.userEmail ||
                                                        "-"}
                                                </span>
                                            </div>

                                            {/* 신청일 */}
                                            <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                                <span className="text-sm font-medium text-zinc-500">
                                                    신청일
                                                </span>

                                                <span className="text-right text-sm font-semibold text-zinc-800">
                                                    {formatDateTime(
                                                        change.requestCreatedAt,
                                                    )}
                                                </span>
                                            </div>

                                            {/* 신청 승인일 */}
                                            <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                                <span className="text-sm font-medium text-zinc-500">
                                                    신청 승인일
                                                </span>

                                                <span className="text-right text-sm font-semibold text-zinc-800">
                                                    {formatDateTime(
                                                        change.requestUpdatedAt,
                                                    )}
                                                </span>
                                            </div>
                                        </div>
                                    ) : (
                                        <div className="mt-4 rounded-xl bg-zinc-50 px-4 py-5">
                                            <p className="text-sm font-medium text-zinc-600">
                                                별도의 예산 신청 없이
                                                처리된 예산 변경입니다.
                                            </p>
                                        </div>
                                    )}
                                </article>
                            </div>

                            {/* =========================
                                오른쪽 영역
                            ========================== */}
                            <div className="space-y-6">
                                {/* =========================
                                    정산자 정보
                                ========================== */}
                                <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6">
                                    <h2 className="text-lg font-bold text-zinc-800">
                                        정산자 정보
                                    </h2>

                                    <div className="mt-4 space-y-3">
                                        {/* 이름 */}
                                        <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                            <span className="text-sm font-medium text-zinc-500">
                                                이름
                                            </span>

                                            <span className="text-right text-sm font-bold text-zinc-800">
                                                {change.userName ||
                                                    "-"}
                                            </span>
                                        </div>

                                        {/* 이메일 */}
                                        <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                            <span className="text-sm font-medium text-zinc-500">
                                                이메일
                                            </span>

                                            <span className="break-all text-right text-sm font-semibold text-zinc-700">
                                                {change.userEmail ||
                                                    "-"}
                                            </span>
                                        </div>
                                    </div>
                                </article>

                                {/* =========================
                                    처리 정보
                                ========================== */}
                                <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6">
                                    <h2 className="text-lg font-bold text-zinc-800">
                                        처리 정보
                                    </h2>

                                    <div className="mt-4 space-y-3">
                                        {/* 예산 유형 */}
                                        <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                            <span className="text-sm font-medium text-zinc-500">
                                                예산 유형
                                            </span>

                                            <span className="rounded-full bg-indigo-50 px-3 py-1.5 text-xs font-bold text-indigo-600">
                                                {formatBudgetType(
                                                    change.budgetType,
                                                )}
                                            </span>
                                        </div>

                                        {/* 처리 일시 */}
                                        <div className="flex items-center justify-between gap-4 rounded-xl bg-zinc-50 px-4 py-3">
                                            <span className="text-sm font-medium text-zinc-500">
                                                처리 일시
                                            </span>

                                            <span className="text-right text-sm font-semibold text-zinc-800">
                                                {formatDateTime(
                                                    change.processedAt,
                                                )}
                                            </span>
                                        </div>
                                    </div>
                                </article>

                                {/* =========================
                                    현재 방 예산
                                ========================== */}
                                <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6">
                                    <div className="flex items-center justify-between gap-4">
                                        <h2 className="text-lg font-bold text-zinc-800">
                                            현재 방 예산
                                        </h2>

                                        {budget && (
                                            <span className="text-xs font-medium text-zinc-400">
                                                {
                                                    budget.currency
                                                }
                                            </span>
                                        )}
                                    </div>

                                    {budget ? (
                                        <div className="mt-4 space-y-3">
                                            {/* 총 예산 */}
                                            <div className="rounded-xl bg-zinc-50 px-4 py-3">
                                                <div className="flex items-center justify-between gap-4">
                                                    <span className="text-sm font-medium text-zinc-500">
                                                        총 예산
                                                    </span>

                                                    <span className="text-base font-extrabold text-zinc-900">
                                                        {formatMoney(
                                                            budget.totalBudget,
                                                            budget.currency,
                                                        )}
                                                    </span>
                                                </div>
                                            </div>

                                            {/* 가용 예산 */}
                                            <div className="rounded-xl bg-emerald-50 px-4 py-3">
                                                <div className="flex items-center justify-between gap-4">
                                                    <span className="text-sm font-medium text-emerald-700">
                                                        가용 예산
                                                    </span>

                                                    <span className="text-base font-extrabold text-emerald-600">
                                                        {formatMoney(
                                                            budget.availableBudget,
                                                            budget.currency,
                                                        )}
                                                    </span>
                                                </div>
                                            </div>

                                            {/* 예약 예산 */}
                                            <div className="rounded-xl bg-indigo-50 px-4 py-3">
                                                <div className="flex items-center justify-between gap-4">
                                                    <span className="text-sm font-medium text-indigo-700">
                                                        예약 예산
                                                    </span>

                                                    <span className="text-base font-extrabold text-indigo-600">
                                                        {formatMoney(
                                                            budget.reserveBudget,
                                                            budget.currency,
                                                        )}
                                                    </span>
                                                </div>
                                            </div>

                                            {/* 모임 */}
                                            <div className="flex items-center justify-between gap-4 border-t border-zinc-100 pt-3">
                                                <span className="text-sm font-semibold text-zinc-800">
                                                    모임
                                                </span>

                                                <span className="text-right text-sm font-semibold text-zinc-700">
                                                    {
                                                        budget.roomName
                                                    }
                                                </span>
                                            </div>
                                        </div>
                                    ) : (
                                        <div className="mt-4 rounded-xl bg-zinc-50 px-4 py-6 text-center text-sm text-zinc-400">
                                            예산 정보를
                                            불러오는 중입니다.
                                        </div>
                                    )}
                                </article>
                            </div>
                        </div>
                    )}
            </div>
        </main>
    );
}