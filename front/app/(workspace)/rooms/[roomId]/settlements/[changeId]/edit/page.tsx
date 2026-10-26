"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";

import { ApiError } from "@/app/lib/api/types";

import {
    getBudget,
    type Budget,
} from "@/app/lib/api/budgetApi";

import {
    getBudgetChange,
    updateBudgetChange,
    type BudgetChangeDetailResponse,
    type BudgetChangeUpdateRequest,
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
        : "정산 정보를 불러오지 못했습니다.";
}

export default function SettlementEditPage() {
    const params = useParams();
    const router = useRouter();

    const roomId = Number(params.roomId);
    const changeId = Number(params.changeId);

    const [change, setChange] =
        useState<BudgetChangeDetailResponse | null>(
            null,
        );

    const [budget, setBudget] =
        useState<Budget | null>(null);

    const [loading, setLoading] =
        useState(true);

    const [submitting, setSubmitting] =
        useState(false);

    const [error, setError] =
        useState<string | null>(null);

    const [changeBudget, setChangeBudget] =
        useState("");

    const [changedBudget, setChangedBudget] =
        useState("");

    const [userName, setUserName] =
        useState("");

    const [userEmail, setUserEmail] =
        useState("");

    const [reason, setReason] =
        useState("");

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
            ] = await Promise.all([
                getBudgetChange(
                    roomId,
                    changeId,
                ),
                getBudget(roomId),
            ]);

            setChange(changeData);
            setBudget(budgetData);

            setChangeBudget(
                changeData.changeBudget.toLocaleString(
                    "ko-KR",
                ),
            );

            setChangedBudget(
                changeData.changedBudget.toLocaleString(
                    "ko-KR",
                ),
            );

            setUserName(
                changeData.userName ?? "",
            );

            setUserEmail(
                changeData.userEmail ?? "",
            );

            setReason(
                changeData.reason ?? "",
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
                ] = await Promise.all([
                    getBudgetChange(
                        roomId,
                        changeId,
                    ),
                    getBudget(roomId),
                ]);

                if (cancelled) {
                    return;
                }

                setChange(changeData);
                setBudget(budgetData);

                setChangeBudget(
                    changeData.changeBudget.toLocaleString(
                        "ko-KR",
                    ),
                );

                setChangedBudget(
                    changeData.changedBudget.toLocaleString(
                        "ko-KR",
                    ),
                );

                setUserName(
                    changeData.userName ?? "",
                );

                setUserEmail(
                    changeData.userEmail ?? "",
                );

                setReason(
                    changeData.reason ?? "",
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
        Number(
            changeBudget.replace(/,/g, ""),
        ) || 0;

    const spentAmount =
        Number(
            changedBudget.replace(/,/g, ""),
        ) || 0;

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

    const handleNumberChange = (
        value: string,
        setter: (
            value: string,
        ) => void,
    ) => {
        const onlyNumbers =
            value.replace(/[^0-9]/g, "");

        if (!onlyNumbers) {
            setter("");
            return;
        }

        setter(
            Number(
                onlyNumbers,
            ).toLocaleString("ko-KR"),
        );
    };

    const handleSubmit = async (
        event: React.FormEvent<HTMLFormElement>,
    ) => {
        event.preventDefault();

        const nextChangeBudget =
            Number(
                changeBudget.replace(
                    /,/g,
                    "",
                ),
            ) || 0;

        const nextChangedBudget =
            Number(
                changedBudget.replace(
                    /,/g,
                    "",
                ),
            ) || 0;

        if (nextChangeBudget <= 0) {
            setError(
                "승인 금액은 0보다 커야 합니다.",
            );
            return;
        }

        if (nextChangedBudget <= 0) {
            setError(
                "지출 금액은 0보다 커야 합니다.",
            );
            return;
        }

        if (!userName.trim()) {
            setError(
                "정산자 이름을 입력해주세요.",
            );
            return;
        }

        if (!userEmail.trim()) {
            setError(
                "정산자 이메일을 입력해주세요.",
            );
            return;
        }

        if (!reason.trim()) {
            setError(
                "지출 내용을 입력해주세요.",
            );
            return;
        }

        if (reason.length > 20) {
            setError(
                "예산 변동 사유는 최대 20자까지 입력할 수 있습니다.",
            );
            return;
        }

        const request: BudgetChangeUpdateRequest =
            {
                changeBudget:
                nextChangeBudget,
                changedBudget:
                nextChangedBudget,
                userName:
                    userName.trim(),
                userEmail:
                    userEmail.trim(),
                reason:
                    reason.trim(),
            };

        setSubmitting(true);
        setError(null);

        try {
            await updateBudgetChange(
                roomId,
                changeId,
                request,
            );

            router.push(
                `/rooms/${roomId}/settlements/${changeId}`,
            );
        } catch (caughtError) {
            setError(
                getErrorMessage(caughtError),
            );
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <main className="min-h-screen bg-[#f8f8fb] text-zinc-900">
            <div className="mx-auto max-w-5xl px-5 py-10 sm:px-8">
                {/* =========================
                    제목
                ========================== */}
                <div>
                    <p className="text-sm font-medium text-indigo-600">
                        정산 내역 / 수정
                    </p>

                    <h1 className="mt-1 text-3xl font-bold tracking-tight text-zinc-900">
                        정산 수정
                    </h1>

                    <p className="mt-2 text-sm text-zinc-500">
                        정산 금액과 정산자 정보를
                        수정할 수 있습니다.
                    </p>
                </div>

                {/* =========================
                    로딩
                ========================== */}
                {loading && (
                    <div className="mt-8 rounded-2xl border border-zinc-200 bg-white px-6 py-16 text-center text-sm text-zinc-500 shadow-sm">
                        정산 정보를
                        불러오는 중...
                    </div>
                )}

                {/* =========================
                    에러
                ========================== */}
                {!loading && error && !change && (
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
                    수정 내용
                ========================== */}
                {!loading &&
                    change && (
                        <form
                            onSubmit={handleSubmit}
                            className="mt-8"
                        >
                            {/* validation error */}
                            {error && (
                                <div className="mb-6 rounded-2xl border border-red-200 bg-red-50 px-5 py-4">
                                    <p className="text-sm font-medium text-red-600">
                                        {error}
                                    </p>
                                </div>
                            )}

                            {/* =========================
                                상세 페이지와 동일한
                                좌우 레이아웃
                            ========================== */}
                            <div className="grid gap-6 lg:grid-cols-[minmax(0,1.55fr)_minmax(280px,1fr)]">
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
                                                    수정하세요.
                                                </p>
                                            </div>

                                            <span className="shrink-0 rounded-full bg-indigo-50 px-3 py-1.5 text-xs font-bold text-indigo-600">
                                                수정 가능
                                            </span>
                                        </div>

                                        <div className="mt-5 grid gap-3 sm:grid-cols-3">
                                            {/* 승인 금액 */}
                                            <div className="rounded-xl bg-indigo-50 px-4 py-4 ring-1 ring-indigo-200 transition">
                                                <div className="flex items-center justify-between gap-2">
                                                    <label
                                                        htmlFor="changeBudget"
                                                        className="text-xs font-semibold text-indigo-500"
                                                    >
                                                        승인 금액
                                                    </label>

                                                    <span className="text-[10px] font-bold text-indigo-400">
                                                        수정
                                                    </span>
                                                </div>

                                                <div className="mt-1.5 flex items-center gap-1">
                                                    {currency !== "KRW" && (
                                                        <span className="text-base font-extrabold text-indigo-700">
                                                            {currency === "USD" ? "$" : "¥"}
                                                        </span>
                                                    )}

                                                    <input
                                                        id="changeBudget"
                                                        type="text"
                                                        inputMode="numeric"
                                                        value={
                                                            changeBudget
                                                        }
                                                        onChange={(
                                                            event,
                                                        ) =>
                                                            handleNumberChange(
                                                                event
                                                                    .target
                                                                    .value,
                                                                setChangeBudget,
                                                            )
                                                        }
                                                        className="min-w-0 flex-1 bg-transparent text-lg font-extrabold text-indigo-700 outline-none"
                                                    />
                                                    {currency === "KRW" && (
                                                        <span className="text-base font-extrabold text-indigo-700">
                                                            원
                                                        </span>
                                                    )}
                                                </div>
                                            </div>

                                            {/* 지출 금액 */}
                                            <div className="rounded-xl bg-indigo-50 px-4 py-4 ring-1 ring-indigo-200 transition">
                                                <div className="flex items-center justify-between gap-2">
                                                    <label
                                                        htmlFor="changedBudget"
                                                        className="text-xs font-semibold text-indigo-500"
                                                    >
                                                        지출 금액
                                                    </label>

                                                    <span className="text-[10px] font-bold text-indigo-400">
                                                        수정
                                                    </span>
                                                </div>

                                                <div className="mt-1.5 flex items-center gap-1">
                                                    {currency !== "KRW" && (
                                                        <span className="text-base font-extrabold text-indigo-700">
                                                            {currency === "USD" ? "$" : "¥"}
                                                        </span>
                                                        )}

                                                    <input
                                                        id="changedBudget"
                                                        type="text"
                                                        inputMode="numeric"
                                                        value={
                                                            changedBudget
                                                        }
                                                        onChange={(
                                                            event,
                                                        ) =>
                                                            handleNumberChange(
                                                                event
                                                                    .target
                                                                    .value,
                                                                setChangedBudget,
                                                            )
                                                        }
                                                        className="min-w-0 flex-1 bg-transparent text-lg font-extrabold text-indigo-700 outline-none"
                                                    />

                                                    {currency === "KRW" && (
                                                        <span className="text-base font-extrabold text-indigo-700">
                                                            원
                                                        </span>
                                                    )}
                                                </div>
                                            </div>

                                            {/* 반환 / 추가 지출 */}
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
                                        <div className="flex items-center justify-between gap-4">
                                            <div>
                                                <h2 className="text-lg font-bold text-zinc-800">
                                                    지출 내용
                                                </h2>
                                            </div>

                                            <span className="shrink-0 rounded-full bg-indigo-50 px-3 py-1.5 text-xs font-bold text-indigo-600">
                                                수정 가능
                                            </span>
                                        </div>

                                        <div className="mt-4 rounded-xl bg-indigo-50 px-4 py-3 ring-1 ring-indigo-200 transition">
                                            <textarea
                                                value={reason}
                                                onChange={(
                                                    event,
                                                ) =>
                                                    setReason(
                                                        event
                                                            .target
                                                            .value,
                                                    )
                                                }
                                                maxLength={
                                                    20
                                                }
                                                rows={1}
                                                placeholder="지출 내용을 입력해주세요."
                                                className="min-h-10 w-full resize-none bg-transparent py-1 text-sm leading-6 text-zinc-700 outline-none"
                                            />
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
                                        <div className="flex items-center justify-between gap-4">
                                            <h2 className="text-lg font-bold text-zinc-800">
                                                정산자 정보
                                            </h2>

                                            <span className="shrink-0 rounded-full bg-indigo-50 px-3 py-1.5 text-xs font-bold text-indigo-600">
                                                수정 가능
                                            </span>
                                        </div>

                                        <div className="mt-4 space-y-3">
                                            {/* 이름 */}
                                            <div className="flex items-center gap-4 rounded-xl bg-indigo-50 px-4 py-3 ring-1 ring-indigo-200 transition">
                                                <label
                                                    htmlFor="userName"
                                                    className="shrink-0 text-sm font-medium text-indigo-500"
                                                >
                                                    이름
                                                </label>

                                                <input
                                                    id="userName"
                                                    type="text"
                                                    value={
                                                        userName
                                                    }
                                                    onChange={(
                                                        event,
                                                    ) =>
                                                        setUserName(
                                                            event
                                                                .target
                                                                .value,
                                                        )
                                                    }
                                                    className="min-w-0 flex-1 bg-transparent text-right text-sm font-bold text-zinc-800 outline-none"
                                                />
                                            </div>

                                            {/* 이메일 */}
                                            <div className="flex items-center gap-4 rounded-xl bg-indigo-50 px-4 py-3 ring-1 ring-indigo-200 transition">
                                                <label
                                                    htmlFor="userEmail"
                                                    className="shrink-0 text-sm font-medium text-indigo-500"
                                                >
                                                    이메일
                                                </label>

                                                <input
                                                    id="userEmail"
                                                    type="email"
                                                    value={
                                                        userEmail
                                                    }
                                                    onChange={(
                                                        event,
                                                    ) =>
                                                        setUserEmail(
                                                            event
                                                                .target
                                                                .value,
                                                        )
                                                    }
                                                    className="min-w-0 flex-1 bg-transparent text-right text-sm font-semibold text-zinc-700 outline-none"
                                                />
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

                            {/* =========================
                                하단 버튼
                            ========================== */}
                            <div className="mt-6 flex justify-end gap-3">
                                <Link
                                    href={`/rooms/${roomId}/settlements/${changeId}`}
                                    className="rounded-lg border border-zinc-200 bg-white px-4 py-2.5 text-sm font-semibold text-zinc-600 transition-colors hover:border-zinc-300 hover:bg-zinc-50"
                                >
                                    취소
                                </Link>

                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
                                >
                                    {submitting
                                        ? "저장 중..."
                                        : "수정사항 저장"}
                                </button>
                            </div>
                        </form>
                    )}
            </div>
        </main>
    );
}