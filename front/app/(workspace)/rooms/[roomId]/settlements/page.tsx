"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";

import ConfirmDialog from "@/app/components/common/ConfigmDialog/ConfirmDialog";

import {
    getBudget,
    type Budget,
} from "@/app/lib/api/budgetApi";

import {
    getBudgetRequests,
    type BudgetRequest,
} from "@/app/lib/api/budgetRequestApi";

import {
    createBudgetChange,
    getSettlementChanges,
    type BudgetChange,
} from "@/app/lib/api/budgetChangeApi";

import { ApiError } from "@/app/lib/api/types";

const settlementSteps = [
    "승인된 예산 신청 건을 선택하세요",
    "실제 지출한 금액을 입력하세요",
    "차액이 있으면 예산으로 자동 반환돼요",
    "정산 내역은 모든 멤버가 볼 수 있어요",
];

function formatMoney(amount: number, currency: "KRW" | "USD" | "JPY") {
    const symbols = { KRW: "원", USD: "$", JPY: "¥" };

    const locales = { KRW: "ko-KR", USD: "en-US", JPY: "ja-JP" };
    const locale = locales[currency] || "ko-KR";

    const formattedAmount = amount.toLocaleString(locale);

    return currency === "KRW"
        ? `${formattedAmount}${symbols[currency]}`
        : `${symbols[currency]}${formattedAmount}`;
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

function formatDate(
    date: string | null | undefined,
) {
    if (!date) {
        return "-";
    }

    return date.slice(0, 10);
}

export default function SettlementPage() {
    const params = useParams();

    const roomId = Number(
        params.roomId ?? params.id,
    );

    const [budget, setBudget] =
        useState<Budget | null>(null);

    const [requests, setRequests] =
        useState<BudgetRequest[]>([]);

    const [changes, setChanges] =
        useState<BudgetChange[]>([]);

    const [loading, setLoading] =
        useState(true);

    const [submitting, setSubmitting] =
        useState(false);

    const [error, setError] =
        useState<string | null>(null);

    const [selectedRequestId, setSelectedRequestId] =
        useState(0);

    const [spentAmount, setSpentAmount] =
        useState("");

    const [description, setDescription] =
        useState("");

    // 승인 신청 드롭다운
    const [
        isRequestDropdownOpen,
        setIsRequestDropdownOpen,
    ] = useState(false);

    const requestDropdownRef =
        useRef<HTMLDivElement | null>(null);

    // 정산 확인 모달
    const [
        isConfirmDialogOpen,
        setIsConfirmDialogOpen,
    ] = useState(false);

    const approvedRequests =
        requests.filter(
            (request) =>
                request.status.toUpperCase() ===
                "APPROVE",
        );

    const selectedRequest =
        approvedRequests.find(
            (request) =>
                request.id ===
                selectedRequestId,
        ) ?? approvedRequests[0];

    const currency =
        budget?.currency ?? "KRW";

    const spent =
        Number(
            spentAmount.replace(/,/g, ""),
        ) || 0;

    const approvedAmount =
        selectedRequest?.requestedAmount ?? 0;

    /*
     * 승인 금액 - 실제 지출 금액
     *
     * 양수  → 반환 금액
     * 0     → 차액 없음
     * 음수  → 추가 지출
     */
    const balanceAmount =
        approvedAmount - spent;

    const refundAmount =
        Math.max(balanceAmount, 0);

    const additionalExpense =
        Math.max(-balanceAmount, 0);

    // 정산 데이터 불러오기
    const loadSettlementData =
        async () => {
            if (
                !Number.isFinite(roomId) ||
                roomId <= 0
            ) {
                setError(
                    "올바른 모임 정보가 없습니다.",
                );
                setLoading(false);
                return;
            }

            setLoading(true);
            setError(null);

            try {
                const [
                    budgetData,
                    requestData,
                    changeData,
                ] = await Promise.all([
                    getBudget(roomId),
                    getBudgetRequests(roomId),
                    getSettlementChanges(roomId),
                ]);

                setBudget(budgetData);
                setRequests(requestData);
                setChanges(changeData.changes);

                const approved =
                    requestData.filter(
                        (request) =>
                            request.status.toUpperCase() ===
                            "APPROVE",
                    );

                setSelectedRequestId(
                    approved[0]?.id ?? 0,
                );
            } catch (caughtError) {
                setError(
                    getErrorMessage(
                        caughtError,
                    ),
                );
            } finally {
                setLoading(false);
            }
        };

    // 최초 데이터 로딩
    useEffect(() => {
        let cancelled = false;

        const load = async () => {
            if (
                !Number.isFinite(roomId) ||
                roomId <= 0
            ) {
                setError(
                    "올바른 모임 정보가 없습니다.",
                );
                setLoading(false);
                return;
            }

            setLoading(true);
            setError(null);

            try {
                const [
                    budgetData,
                    requestData,
                    changeData,
                ] = await Promise.all([
                    getBudget(roomId),
                    getBudgetRequests(roomId),
                    getSettlementChanges(roomId),
                ]);

                if (cancelled) {
                    return;
                }

                setBudget(budgetData);
                setRequests(requestData);
                setChanges(changeData.changes);

                const approved =
                    requestData.filter(
                        (request) =>
                            request.status.toUpperCase() ===
                            "APPROVE",
                    );

                setSelectedRequestId(
                    approved[0]?.id ?? 0,
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
    }, [roomId]);

    // 드롭다운 바깥 클릭 시 닫기
    useEffect(() => {
        const handleClickOutside = (
            event: MouseEvent,
        ) => {
            if (
                requestDropdownRef.current &&
                !requestDropdownRef.current.contains(
                    event.target as Node,
                )
            ) {
                setIsRequestDropdownOpen(
                    false,
                );
            }
        };

        document.addEventListener(
            "mousedown",
            handleClickOutside,
        );

        return () => {
            document.removeEventListener(
                "mousedown",
                handleClickOutside,
            );
        };
    }, []);

    // ESC 키로 드롭다운 닫기
    useEffect(() => {
        const handleKeyDown = (
            event: KeyboardEvent,
        ) => {
            if (event.key === "Escape") {
                setIsRequestDropdownOpen(
                    false,
                );
            }
        };

        document.addEventListener(
            "keydown",
            handleKeyDown,
        );

        return () => {
            document.removeEventListener(
                "keydown",
                handleKeyDown,
            );
        };
    }, []);

    // 정산 완료 버튼 클릭
    // 여기서는 API를 호출하지 않고
    // 확인 모달만 열어준다.
    const handleSubmit = () => {
        if (!selectedRequest) {
            return;
        }

        if (spent <= 0) {
            setError(
                "실제 지출 금액을 입력해주세요.",
            );
            return;
        }

        if (
            spent >
            selectedRequest.requestedAmount
        ) {
            setError(
                "실제 지출 금액은 승인 금액을 초과할 수 없습니다.",
            );
            return;
        }

        setError(null);
        setIsConfirmDialogOpen(true);
    };

    // 확인 모달에서
    // "정산하기"를 눌렀을 때
    const handleConfirmSettlement =
        async () => {
            if (!selectedRequest) {
                return;
            }

            setSubmitting(true);
            setError(null);

            try {
                await createBudgetChange(
                    roomId,
                    selectedRequest.id,
                    {
                        changedBudget: spent,
                        reason: description,
                    },
                );

                // 모달 닫기
                setIsConfirmDialogOpen(false);

                // 입력값 초기화
                setSpentAmount("");
                setDescription("");

                // 정산 내역 및 예산 새로고침
                await loadSettlementData();
            } catch (caughtError) {
                setError(
                    getErrorMessage(
                        caughtError,
                    ),
                );
            } finally {
                setSubmitting(false);
            }
        };

    return (
        <section
            aria-labelledby="settlement-title"
            className="mx-auto w-full max-w-5xl"
        >
            <header>
                <h1
                    id="settlement-title"
                    className="text-2xl font-bold tracking-tight text-zinc-900"
                >
                    정산하기
                </h1>

                <p className="mt-1 text-sm text-zinc-500">
                    승인된 예산을 실제 지출 내역과
                    함께 정산하세요
                </p>
            </header>

            {/* =========================
                로딩
            ========================== */}
            {loading && (
                <div className="mt-4 rounded-xl border border-zinc-200 bg-white px-4 py-3 text-sm text-zinc-500">
                    정산 정보를 불러오는 중...
                </div>
            )}

            {/* =========================
                에러
            ========================== */}
            {error && (
                <div className="mt-4 flex items-center justify-between gap-4 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600">
                    <span>{error}</span>

                    <button
                        type="button"
                        onClick={
                            loadSettlementData
                        }
                        className="shrink-0 rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-semibold text-red-600"
                    >
                        다시 시도
                    </button>
                </div>
            )}

            {/* =========================
                예산 정보 없음
            ========================== */}
            {!loading &&
                !error &&
                !budget && (
                    <div className="mt-4 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-700">
                        해당하는 모임을
                        찾을 수 없습니다.
                    </div>
                )}

            <div className="mt-6 grid max-w-4xl gap-6 lg:grid-cols-[minmax(0,1.55fr)_minmax(280px,1fr)]">
                {/* =========================
                    새 정산
                ========================== */}
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        handleSubmit();
                    }}
                    className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm sm:p-6"
                >
                    <h2 className="text-lg font-bold text-zinc-800">
                        새 정산
                    </h2>

                    {/* 승인된 예산 신청 */}
                    <div className="mt-4">
                        <label className="text-sm font-semibold text-zinc-800">
                            승인된 예산 신청
                        </label>

                        {approvedRequests.length >
                        0 ? (
                            <div
                                ref={
                                    requestDropdownRef
                                }
                                className="relative mt-2"
                            >
                                {/* 현재 선택된 항목 */}
                                <button
                                    type="button"
                                    onClick={() =>
                                        setIsRequestDropdownOpen(
                                            (open) =>
                                                !open,
                                        )
                                    }
                                    aria-haspopup="listbox"
                                    aria-expanded={
                                        isRequestDropdownOpen
                                    }
                                    className={`flex h-12 w-full items-center justify-between rounded-xl border bg-white px-4 text-left transition ${
                                        isRequestDropdownOpen
                                            ? "border-indigo-400 ring-2 ring-indigo-100"
                                            : "border-zinc-200 hover:border-zinc-300"
                                    }`}
                                >
                                    <div className="min-w-0">
                                        <p className="truncate text-sm font-semibold text-zinc-900">
                                            {selectedRequest?.reason ??
                                                "승인된 예산을 선택하세요"}
                                        </p>

                                        {selectedRequest && (
                                            <p className="mt-0.5 text-xs text-zinc-400">
                                                승인 금액{" "}
                                                {formatMoney(
                                                    selectedRequest.requestedAmount,
                                                    currency,
                                                )}
                                            </p>
                                        )}
                                    </div>

                                    {/* 화살표 */}
                                    <svg
                                        className={`ml-4 h-4 w-4 shrink-0 text-zinc-400 transition-transform ${
                                            isRequestDropdownOpen
                                                ? "rotate-180"
                                                : ""
                                        }`}
                                        viewBox="0 0 20 20"
                                        fill="none"
                                        aria-hidden="true"
                                    >
                                        <path
                                            d="M5 7.5L10 12.5L15 7.5"
                                            stroke="currentColor"
                                            strokeWidth="1.8"
                                            strokeLinecap="round"
                                            strokeLinejoin="round"
                                        />
                                    </svg>
                                </button>

                                {/* 드롭다운 목록 */}
                                {isRequestDropdownOpen && (
                                    <div
                                        role="listbox"
                                        className="absolute left-0 right-0 z-30 mt-2 overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-xl shadow-zinc-200/50"
                                    >
                                        <div className="request-dropdown-scroll max-h-[250px] overflow-y-auto p-1.5">
                                            {approvedRequests.map(
                                                (
                                                    request,
                                                ) => {
                                                    const isSelected =
                                                        request.id ===
                                                        selectedRequestId;

                                                    return (
                                                        <button
                                                            key={
                                                                request.id
                                                            }
                                                            type="button"
                                                            role="option"
                                                            aria-selected={
                                                                isSelected
                                                            }
                                                            onClick={() => {
                                                                setSelectedRequestId(
                                                                    request.id,
                                                                );

                                                                setIsRequestDropdownOpen(
                                                                    false,
                                                                );
                                                            }}
                                                            className={`flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition ${
                                                                isSelected
                                                                    ? "bg-indigo-50"
                                                                    : "hover:bg-zinc-50"
                                                            }`}
                                                        >
                                                            {/* 작은 체크 박스 */}
                                                            <span
                                                                className={`flex h-5 w-5 shrink-0 items-center justify-center rounded-md border transition ${
                                                                    isSelected
                                                                        ? "border-indigo-600 bg-indigo-600"
                                                                        : "border-zinc-300 bg-white"
                                                                }`}
                                                            >
                                                                {isSelected && (
                                                                    <svg
                                                                        className="h-3 w-3 text-white"
                                                                        viewBox="0 0 20 20"
                                                                        fill="none"
                                                                        aria-hidden="true"
                                                                    >
                                                                        <path
                                                                            d="M4 10.5L8 14L16 6"
                                                                            stroke="currentColor"
                                                                            strokeWidth="2.2"
                                                                            strokeLinecap="round"
                                                                            strokeLinejoin="round"
                                                                        />
                                                                    </svg>
                                                                )}
                                                            </span>

                                                            {/* 신청 내용 */}
                                                            <div className="min-w-0 flex-1">
                                                                <p
                                                                    className={`truncate text-sm font-medium ${
                                                                        isSelected
                                                                            ? "text-indigo-900"
                                                                            : "text-zinc-800"
                                                                    }`}
                                                                >
                                                                    {
                                                                        request.reason
                                                                    }
                                                                </p>

                                                                <p className="mt-0.5 text-[11px] text-zinc-400">
                                                                    신청일{" "}
                                                                    {formatDate(
                                                                        request.createdAt,
                                                                    )}
                                                                </p>
                                                            </div>

                                                            {/* 승인 금액 */}
                                                            <span
                                                                className={`shrink-0 text-xs font-bold ${
                                                                    isSelected
                                                                        ? "text-indigo-600"
                                                                        : "text-zinc-600"
                                                                }`}
                                                            >
                                                                {formatMoney(
                                                                    request.requestedAmount,
                                                                    currency,
                                                                )}
                                                            </span>
                                                        </button>
                                                    );
                                                },
                                            )}
                                        </div>
                                    </div>
                                )}
                            </div>
                        ) : (
                            <div className="mt-2 rounded-xl border border-dashed border-zinc-300 bg-zinc-50 px-4 py-4 text-sm text-zinc-500">
                                정산할 수 있는
                                승인된 예산 신청이
                                없습니다.
                            </div>
                        )}
                    </div>

                    {/* 승인 금액 */}
                    <div className="mt-4 rounded-xl bg-indigo-50 p-4">
                        <div className="flex items-center justify-between">
                            <span className="text-sm font-medium text-indigo-600">
                                승인 금액
                            </span>

                            <span className="text-lg font-extrabold text-indigo-700">
                                {selectedRequest
                                    ? formatMoney(
                                        selectedRequest.requestedAmount,
                                        currency,
                                    )
                                    : "-"}
                            </span>
                        </div>
                    </div>

                    {/* 실제 지출 금액 */}
                    <div className="mt-4">
                        <label
                            htmlFor="spentAmount"
                            className="text-sm font-semibold text-zinc-800"
                        >
                            실제 지출 금액
                        </label>

                        <div className="mt-2 flex h-12 items-center rounded-xl border border-zinc-200 px-4 transition focus-within:ring-2 focus-within:ring-indigo-100">
                            <input
                                id="spentAmount"
                                inputMode="numeric"
                                value={spentAmount}
                                onChange={(event) => {
                                    const value =
                                        event.target.value.replace(
                                            /[^0-9]/g,
                                            "",
                                        );

                                    setSpentAmount(
                                        value,
                                    );
                                }}
                                placeholder="0"
                                className="min-w-0 flex-1 bg-transparent text-base text-zinc-900 outline-none"
                            />

                            <span className="text-sm font-medium text-zinc-500">
                                {currency ===
                                "KRW"
                                    ? "원"
                                    : currency}
                            </span>
                        </div>
                    </div>

                    {/* 지출 내용 */}
                    <div className="mt-4">
                        <label
                            htmlFor="description"
                            className="text-sm font-semibold text-zinc-800"
                        >
                            지출 내용
                        </label>

                        <textarea
                            id="description"
                            rows={3}
                            value={description}
                            onChange={(event) =>
                                setDescription(
                                    event.target
                                        .value,
                                )
                            }
                            placeholder="예: 온사인관에서 사진 30장 인화 및 액자 제작"
                            className="mt-2 w-full resize-none rounded-xl border border-zinc-200 px-4 py-3 text-sm text-zinc-900 outline-none focus:ring-2 focus:ring-indigo-100"
                        />
                    </div>

                    {/* 반환 / 추가 지출 */}
                    <div
                        className={`mt-4 flex items-center justify-between rounded-xl px-4 py-3 ${
                            refundAmount > 0
                                ? "border border-emerald-100 bg-emerald-50"
                                : additionalExpense >
                                0
                                    ? "border border-red-100 bg-red-50"
                                    : "border border-zinc-200 bg-zinc-50"
                        }`}
                    >
                        <span
                            className={`text-sm font-medium ${
                                refundAmount > 0
                                    ? "text-emerald-700"
                                    : additionalExpense >
                                    0
                                        ? "text-red-700"
                                        : "text-zinc-500"
                            }`}
                        >
                            {refundAmount > 0
                                ? "예산 반환 금액"
                                : additionalExpense >
                                0
                                    ? "추가 지출"
                                    : "반환 / 추가 지출"}
                        </span>

                        <span
                            className={`text-base font-extrabold ${
                                refundAmount > 0
                                    ? "text-emerald-700"
                                    : additionalExpense >
                                    0
                                        ? "text-red-700"
                                        : "text-zinc-500"
                            }`}
                        >
                            {refundAmount > 0
                                ? `+${formatMoney(
                                    refundAmount,
                                    currency,
                                )}`
                                : additionalExpense >
                                0
                                    ? `+${formatMoney(
                                        additionalExpense,
                                        currency,
                                    )}`
                                    : "−"}
                        </span>
                    </div>

                    {/* 정산 버튼 */}
                    <button
                        type="submit"
                        disabled={
                            submitting ||
                            !selectedRequest ||
                            approvedRequests.length ===
                            0
                        }
                        className="mt-4 h-12 w-full rounded-xl bg-indigo-600 text-sm font-semibold text-white shadow-sm transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed"
                    >
                        정산 완료하기
                    </button>
                </form>

                {/* =========================
                    오른쪽 정보
                ========================== */}
                <div className="space-y-4">
                    {/* 현재 방 예산 */}
                    <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm">
                        <div className="flex items-center justify-between">
                            <h2 className="text-sm font-bold text-zinc-500">
                                현재 방 예산
                            </h2>

                            {budget && (
                                <span className="text-xs font-medium text-zinc-400">
                                    {budget.currency}
                                </span>
                            )}
                        </div>

                        {budget ? (
                            <div className="mt-4 space-y-3">
                                {/* 총 예산 */}
                                <div className="rounded-xl bg-zinc-50 px-4 py-3">
                                    <div className="flex items-center justify-between">
                                        <span className="text-sm font-medium text-zinc-500">
                                            총 예산
                                        </span>

                                        <span className="text-lg font-extrabold text-zinc-900">
                                            {formatMoney(
                                                budget.totalBudget,
                                                budget.currency,
                                            )}
                                        </span>
                                    </div>
                                </div>

                                {/* 가용 예산 */}
                                <div className="rounded-xl bg-emerald-50 px-4 py-3">
                                    <div className="flex items-center justify-between">
                                        <span className="text-sm font-medium text-emerald-700">
                                            가용 예산
                                        </span>

                                        <span className="text-lg font-extrabold text-emerald-600">
                                            {formatMoney(
                                                budget.availableBudget,
                                                budget.currency,
                                            )}
                                        </span>
                                    </div>
                                </div>

                                {/* 예비 예산 */}
                                <div className="rounded-xl bg-indigo-50 px-4 py-3">
                                    <div className="flex items-center justify-between">
                                        <span className="text-sm font-medium text-indigo-700">
                                            예약 예산
                                        </span>

                                        <span className="text-lg font-extrabold text-indigo-600">
                                            {formatMoney(
                                                budget.reserveBudget,
                                                budget.currency,
                                            )}
                                        </span>
                                    </div>
                                </div>

                                {/* 모임 이름 */}
                                <div className="border-t border-zinc-200 pt-3">
                                    <div className="flex items-center justify-between">
                                        <span className="text-sm font-semibold text-zinc-800">
                                            모임
                                        </span>

                                        <span className="text-sm font-semibold text-zinc-700">
                                            {budget.roomName}
                                        </span>
                                    </div>
                                </div>
                            </div>
                        ) : (
                            <div className="mt-4 rounded-xl bg-zinc-50 px-4 py-6 text-center text-sm text-zinc-400">
                                예산 정보를
                                불러오는 중입니다.
                            </div>
                        )}
                    </article>

                    {/* 정산 절차 */}
                    <article className="rounded-2xl border border-zinc-200 bg-white p-5 shadow-sm">
                        <h2 className="text-sm font-bold text-indigo-600">
                            정산 절차 안내
                        </h2>

                        <ol className="mt-3 space-y-2.5">
                            {settlementSteps.map(
                                (
                                    step,
                                    index,
                                ) => (
                                    <li
                                        key={step}
                                        className="flex items-center gap-2 text-sm font-medium text-indigo-500"
                                    >
                                        <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-indigo-600 text-xs font-bold text-white">
                                            {index +
                                                1}
                                        </span>

                                        {step}
                                    </li>
                                ),
                            )}
                        </ol>
                    </article>
                </div>
            </div>

            {/* =========================
    정산 내역
========================== */}
            <section
                aria-labelledby="settlements-title"
                className="mt-7 max-w-4xl"
            >
                <div className="flex items-end justify-between gap-4">
                    <div>
                        <h2
                            id="settlements-title"
                            className="text-lg font-bold text-zinc-800"
                        >
                            정산 내역
                        </h2>
                        <p className="mt-1 text-sm text-zinc-500">
                            실제 지출한 금액을 기준으로 정산 내역을 확인할 수 있어요.
                        </p>
                    </div>
                </div>

                <div className="mt-3 overflow-x-auto rounded-2xl border border-zinc-200 bg-white shadow-sm">
                    <table className="min-w-[620px] w-full text-left text-sm">
                        <thead className="border-b border-zinc-200 bg-zinc-50 text-zinc-500">
                        <tr>
                            <th className="px-5 py-3 font-medium">
                                정산일
                            </th>

                            <th className="px-5 py-3 font-medium">
                                내용
                            </th>

                            <th className="px-5 py-3 font-medium">
                                정산자
                            </th>

                            <th className="px-5 py-3 text-right font-medium">
                                지출 금액
                            </th>

                            <th className="px-5 py-3 text-center font-medium">
                                상세조회
                            </th>
                        </tr>
                        </thead>

                        <tbody>
                        {changes.length > 0 ? (
                            changes.map((change) => (
                                <tr
                                    key={change.id}
                                    className="border-b border-zinc-100 last:border-b-0 text-zinc-800"
                                >
                                    {/* 정산일 */}
                                    <td className="px-5 py-4 whitespace-nowrap text-zinc-500">
                                        {change.processedAt
                                            ? formatDate(change.processedAt)
                                            : "-"}
                                    </td>

                                    {/* 내용 */}
                                    <td className="px-5 py-4">
                                        <p className="max-w-[280px] truncate font-semibold text-zinc-800">
                                            {change.reason || "-"}
                                        </p>
                                    </td>

                                    {/* 정산자 */}
                                    <td className="px-5 py-4 whitespace-nowrap text-zinc-600">
                                        {change.userName || "-"}
                                    </td>

                                    {/* 실제 지출 금액 */}
                                    <td className="px-5 py-4 text-right whitespace-nowrap">
                                <span className="font-bold text-zinc-900">
                                    {formatMoney(
                                        change.changedBudget,
                                        currency,
                                    )}
                                </span>
                                    </td>

                                    {/* 상세조회 */}
                                    <td className="px-5 py-4 text-center">
                                        <Link
                                            href={`/rooms/${roomId}/settlements/${change.id}`}
                                            className="inline-flex items-center rounded-lg border border-zinc-200 bg-white px-3 py-2 text-xs font-semibold text-zinc-700 transition-colors hover:border-indigo-200 hover:bg-indigo-50 hover:text-indigo-600"
                                        >
                                            상세조회
                                        </Link>
                                    </td>
                                </tr>
                            ))
                        ) : (
                            <tr>
                                <td
                                    colSpan={5}
                                    className="px-5 py-12 text-center text-sm text-zinc-500"
                                >
                                    아직 정산 내역이 없습니다.
                                </td>
                            </tr>
                        )}
                        </tbody>
                    </table>
                </div>
            </section>

            {/* =========================
                정산 최종 확인 모달
            ========================== */}
            <ConfirmDialog
                open={
                    isConfirmDialogOpen
                }
                title="정산 내용을 확인해주세요"
                description="아래 내용이 맞는지 확인한 후 정산을 진행해주세요."
                confirmLabel="정산하기"
                cancelLabel="취소"
                loading={submitting}
                onCancel={() => {
                    if (!submitting) {
                        setIsConfirmDialogOpen(
                            false,
                        );
                    }
                }}
                onConfirm={
                    handleConfirmSettlement
                }
            >
                <div className="space-y-3">
                    {/* 선택한 승인 신청 */}
                    <div className="rounded-xl border border-zinc-200 bg-zinc-50 px-4 py-3">
                        <p className="text-xs font-semibold text-zinc-400">
                            승인 신청
                        </p>

                        <p className="mt-1 text-sm font-semibold text-zinc-800">
                            {selectedRequest?.reason ??
                                "-"}
                        </p>
                    </div>

                    {/* 승인 금액 */}
                    <div className="flex items-center justify-between rounded-xl bg-indigo-50 px-4 py-3">
                        <span className="text-sm font-medium text-indigo-600">
                            승인 금액
                        </span>

                        <span className="text-base font-extrabold text-indigo-700">
                            {formatMoney(
                                approvedAmount,
                                currency,
                            )}
                        </span>
                    </div>

                    {/* 실제 지출 금액 */}
                    <div className="flex items-center justify-between rounded-xl border border-zinc-200 bg-zinc-50 px-4 py-3">
                        <span className="text-sm font-medium text-zinc-600">
                            실제 지출 금액
                        </span>

                        <span className="text-base font-extrabold text-zinc-900">
                            {formatMoney(
                                spent,
                                currency,
                            )}
                        </span>
                    </div>

                    {/* 반환 / 추가 지출 */}
                    <div
                        className={`flex items-center justify-between rounded-xl px-4 py-3 ${
                            refundAmount > 0
                                ? "border border-emerald-100 bg-emerald-50"
                                : additionalExpense >
                                0
                                    ? "border border-red-100 bg-red-50"
                                    : "border border-zinc-200 bg-zinc-50"
                        }`}
                    >
                        <span
                            className={`text-sm font-medium ${
                                refundAmount > 0
                                    ? "text-emerald-700"
                                    : additionalExpense >
                                    0
                                        ? "text-red-700"
                                        : "text-zinc-500"
                            }`}
                        >
                            {refundAmount > 0
                                ? "예산 반환 금액"
                                : additionalExpense >
                                0
                                    ? "추가 지출"
                                    : "반환 / 추가 지출"}
                        </span>

                        <span
                            className={`text-base font-extrabold ${
                                refundAmount > 0
                                    ? "text-emerald-700"
                                    : additionalExpense >
                                    0
                                        ? "text-red-700"
                                        : "text-zinc-500"
                            }`}
                        >
                            {refundAmount > 0
                                ? `+${formatMoney(
                                    refundAmount,
                                    currency,
                                )}`
                                : additionalExpense >
                                0
                                    ? `+${formatMoney(
                                        additionalExpense,
                                        currency,
                                    )}`
                                    : "−"}
                        </span>
                    </div>

                    {/* 지출 내용 */}
                    <div className="rounded-xl border border-zinc-200 bg-white px-4 py-3">
                        <p className="text-xs font-semibold text-zinc-400">
                            지출 내용
                        </p>

                        <p className="mt-1.5 whitespace-pre-wrap text-sm leading-6 text-zinc-800">
                            {description.trim()
                                ? description
                                : "입력된 지출 내용이 없습니다."}
                        </p>
                    </div>
                </div>
            </ConfirmDialog>
        </section>
    );
}