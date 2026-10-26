"use client";


import { notify } from "@/app/components/common/Feedback";
import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "@/app/components/common/ConfigmDialog/ConfirmDialog";
import { getMe } from "@/app/lib/api/userApi";
import { useParams } from "next/navigation";

import {
    cancelBudgetRequest,
    createBudgetRequest,
    getBudgetRequests,
    type BudgetRequest,
} from "@/app/lib/api/budgetRequestApi";

import {
    getBudget,
    type Budget,
} from "@/app/lib/api/budgetApi";

export default function BudgetRequestsPage() {
    const { roomId: roomIdParam } = useParams<{ roomId: string }>();
    const roomId = Number(roomIdParam);

    const [budget, setBudget] = useState<Budget | null>(null);
    const [requests, setRequests] = useState<BudgetRequest[]>([]);

    const [currentUserId, setCurrentUserId] = useState<number | null>(null);
    const [statusFilter, setStatusFilter] = useState("ALL");
    const [formError, setFormError] = useState<string | null>(null);
    const [amount, setAmount] = useState("");
    const [reason, setReason] = useState("");

    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const loadData = useCallback(async () => {
        if (!roomId || Number.isNaN(roomId)) {
            setError("모임 정보를 찾을 수 없습니다.");
            setLoading(false);
            return;
        }

        try {
            setLoading(true);
            setError(null);

            const [budgetData, requestData, user] = await Promise.all([
                getBudget(roomId),
                getBudgetRequests(roomId),
                getMe(),
            ]);

            setCurrentUserId(user.id);
            setBudget(budgetData);
            setRequests(requestData);
        } catch (error) {
            console.error("예산 신청 정보를 불러오지 못했습니다.", error);

            setError(
                error instanceof Error
                    ? error.message
                    : "예산 신청 정보를 불러오지 못했습니다.",
            );
        } finally {
            setLoading(false);
        }
    }, [roomId]);

    useEffect(() => {
        void Promise.resolve().then(loadData);
    }, [loadData]);

    const handleSubmit = async (event: React.SyntheticEvent<HTMLFormElement>) => {
        event.preventDefault();
        setFormError(null);

        if (!roomId || Number.isNaN(roomId)) {
            notify("모임 정보를 찾을 수 없습니다.");
            return;
        }

        const requestedAmount = Number(amount);

        if (!Number.isSafeInteger(requestedAmount) || requestedAmount <= 0) {
            setFormError("신청 금액은 1 이상의 정수로 입력해 주세요.");
            document.getElementById("amount")?.focus();
            return;
        }

        if (!reason.trim()) {
            setFormError("예산 사용 목적을 입력해 주세요.");
            document.getElementById("reason")?.focus();
            return;
        }

        if (reason.length > 20) {
            setFormError("신청 사유는 20자 이하로 입력해 주세요.");
            return;
        }

        try {
            setSubmitting(true);

            await createBudgetRequest(roomId, {
                reason: reason.trim(),
                requested_amount: requestedAmount,
            });

            setAmount("");
            setReason("");

            await loadData();

            notify("예산 신청이 완료되었습니다.");
        } catch (error) {
            console.error("예산 신청에 실패했습니다.", error);

            notify(
                error instanceof Error
                    ? error.message
                    : "예산 신청에 실패했습니다.",
            );
        } finally {
            setSubmitting(false);
        }
    };

    if (loading) {
        return <LoadingState />;
    }

    if (error) {
        return (
            <section className="mx-auto max-w-5xl">
                <div className="rounded-2xl bg-white p-8 text-center shadow-sm">
                    <p className="text-sm text-red-500">{error}</p>

                    <button
                        type="button"
                        onClick={() => void loadData()}
                        className="mt-4 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
                    >
                        다시 불러오기
                    </button>
                </div>
            </section>
        );
    }

    return (
        <section className="mx-auto max-w-5xl space-y-6">
            {/* 제목 */}
            <div>
                <h1 className="text-2xl font-bold text-zinc-900">
                    예산 신청
                </h1>

                <p className="mt-2 text-sm text-zinc-500">
                    필요한 예산을 신청하고 승인 현황을 확인할 수 있습니다.
                </p>
            </div>

            {/* 현재 예산 */}
            {budget && (
                <div className="grid gap-4 sm:grid-cols-3">
                    <BudgetCard
                        label="현재 잔액"
                        value={budget.totalBudget}
                        currency={budget.currency}
                    />

                    <BudgetCard
                        label="사용 가능 예산"
                        value={budget.availableBudget}
                        currency={budget.currency}
                    />

                    <BudgetCard
                        label="예약 예산"
                        value={budget.reserveBudget}
                        currency={budget.currency}
                    />
                </div>
            )}

            {/* 예산 신청 */}
            <div className="rounded-2xl bg-white p-6 shadow-sm">
                <h2 className="text-lg font-semibold text-zinc-900">
                    예산 신청하기
                </h2>

                <form
                    onSubmit={handleSubmit}
                    className="mt-5 space-y-5"
                >
                    <div>
                        <label
                            htmlFor="amount"
                            className="mb-2 block text-sm font-medium text-zinc-700"
                        >
                            신청 금액
                        </label>

                        <input
                            id="amount"
                            required
                            aria-describedby="request-form-help"
                            type="number"
                            min="1"
                            value={amount}
                            onChange={(event) => setAmount(event.target.value)}
                            placeholder="신청할 금액을 입력해주세요."
                            className="w-full rounded-xl border border-zinc-200 px-4 py-3 text-sm outline-none transition focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
                        />
                    </div>

                    <div>
                        <div className="mb-2 flex items-center justify-between">
                            <label
                                htmlFor="reason"
                                className="text-sm font-medium text-zinc-700"
                            >
                                신청 사유
                            </label>

                            <span className="text-xs text-zinc-400">
                {reason.length}/20
              </span>
                        </div>

                        <input
                            id="reason"
                            required
                            aria-describedby="request-form-help"
                            type="text"
                            maxLength={20}
                            value={reason}
                            onChange={(event) => setReason(event.target.value)}
                            placeholder="예산 사용 목적을 입력해주세요."
                            className="w-full rounded-xl border border-zinc-200 px-4 py-3 text-sm outline-none transition focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
                        />
                    </div>

                    <p id="request-form-help" role={formError ? "alert" : undefined} className={formError ? "text-sm text-red-600" : "text-xs leading-6 text-zinc-500"}>{formError ?? "승인되면 신청 금액이 선점돼요. 사용 후 정산하면 차액이 반환됩니다."}</p>
                    <button
                        type="submit"
                        disabled={submitting}
                        className="w-full rounded-xl bg-indigo-600 px-4 py-3 text-sm font-semibold text-white transition hover:bg-indigo-700 disabled:cursor-not-allowed disabled:bg-zinc-300"
                    >
                        {submitting ? "신청 중..." : "예산 신청하기"}
                    </button>
                </form>
            </div>

            {/* 신청 내역 */}
            <div className="rounded-2xl bg-white p-6 shadow-sm">
                <div className="flex items-center justify-between">
                    <div>
                        <h2 className="text-lg font-semibold text-zinc-900">
                            예산 신청 내역
                        </h2>

                        <p className="mt-1 text-sm text-zinc-500">
                            지금까지 신청한 예산 내역입니다.
                        </p>
                    </div>

                    <span className="text-sm text-zinc-400">
            총 {requests.length}건
          </span>
                </div>

                <div role="group" aria-label="신청 상태 필터" className="mt-5 flex flex-wrap gap-2">{[{ value: "ALL", label: "전체" }, { value: "REQUEST", label: "승인 대기" }, { value: "APPROVE", label: "승인 · 미정산" }, { value: "REJECT", label: "반려" }, { value: "SETTLEMENT", label: "정산 완료" }].map(filter => <button key={filter.value} type="button" aria-pressed={statusFilter === filter.value} onClick={() => setStatusFilter(filter.value)} className={`inline-flex min-h-10 items-center gap-2 rounded-full border px-3.5 py-2 text-xs font-semibold transition ${statusFilter === filter.value ? "border-indigo-600 bg-indigo-600 text-white" : "border-zinc-200 bg-white text-zinc-600 hover:border-indigo-300 hover:text-indigo-700"}`}>{filter.label}<span className={`rounded-full px-1.5 text-[11px] tabular-nums ${statusFilter === filter.value ? "bg-white/20 text-white" : "bg-zinc-100 text-zinc-500"}`}>{filter.value === "ALL" ? requests.length : requests.filter(request => request.status === filter.value).length}</span></button>)}</div>
                {requests.filter(request => statusFilter === "ALL" || request.status === statusFilter).length === 0 ? (
                    <div className="mt-8 rounded-xl bg-zinc-50 py-10 text-center">
                        <p className="text-sm text-zinc-500">
                            조건에 맞는 예산 신청 내역이 없습니다.
                        </p>
                    </div>
                ) : (
                    <div className="mt-5 space-y-3">
                        {requests.filter(request => statusFilter === "ALL" || request.status === statusFilter).map((request) => (
                            <RequestItem
                                key={request.id}
                                request={request}
                                currency={budget?.currency ?? "KRW"}
                                roomId={roomId}
                                canCancel={request.userId === currentUserId}
                                onCanceled={loadData}
                            />
                        ))}
                    </div>
                )}
            </div>
        </section>
    );
}

function LoadingState() {
    return (
        <section className="mx-auto max-w-5xl">
            <div className="rounded-2xl bg-white p-8 text-center shadow-sm">
                <p className="text-sm text-zinc-500">
                    예산 정보를 불러오는 중입니다...
                </p>
            </div>
        </section>
    );
}

function BudgetCard({
                        label,
                        value,
                        currency,
                    }: {
    label: string;
    value: number;
    currency: "KRW" | "USD" | "JPY";
}) {
    return (
        <div className="rounded-2xl bg-white p-5 shadow-sm">
            <p className="text-sm text-zinc-500">{label}</p>

            <p className="mt-2 text-2xl font-bold text-zinc-900">
                {formatMoney(value, currency)}
            </p>
        </div>
    );
}

function RequestItem({
    request,
    currency,
    roomId,
    onCanceled,
    canCancel,
}: {
    request: BudgetRequest;
    currency: "KRW" | "USD" | "JPY";
    roomId: number;
    onCanceled: () => Promise<void>;
    canCancel: boolean;
}) {
    const [confirmCancel, setConfirmCancel] = useState(false);
    const [canceling, setCanceling] = useState(false);

    const handleCancel = async () => {
        if (!roomId || Number.isNaN(roomId)) {
            notify("모임 정보를 찾을 수 없습니다.");
            return;
        }

        if (canceling) return;
        try {
            setCanceling(true);

            await cancelBudgetRequest(roomId, request.id);
            setConfirmCancel(false);
            await onCanceled();

            notify("예산 신청이 취소되었습니다.");
        } catch (error) {
            console.error("예산 신청 취소에 실패했습니다.", error);

            notify(
                error instanceof Error
                    ? error.message
                    : "예산 신청 취소에 실패했습니다.",
            );
        } finally {
            setCanceling(false);
        }
    };

    return (
        <div className="rounded-xl border border-zinc-100 p-4">
            <div className="flex items-start justify-between gap-4">
                <div className="min-w-0 flex-1">
                    <p className="font-semibold text-zinc-900">
                        {request.reason}
                    </p>

                    <p className="mt-1 text-xs text-zinc-400">
                        신청일 {formatDate(request.createdAt)}
                    </p>
                </div>

                <div className="flex shrink-0 items-center gap-3">
                    <p className="font-semibold text-zinc-900">
                        {formatMoney(request.requestedAmount, currency)}
                    </p>

                    {canCancel && request.status !== "SETTLEMENT" && (
                        <button
                            type="button"
                            onClick={() => setConfirmCancel(true)}
                            disabled={canceling}
                            className="rounded-lg border border-red-200 bg-white px-3 py-1.5 text-xs font-semibold text-red-600 transition hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50"
                        >
                            {canceling ? "취소 중..." : "취소"}
                        </button>
                    )}
                </div>
            </div>

            <ConfirmDialog open={confirmCancel} title="예산 신청을 취소할까요?" description={request.status === "APPROVE" ? "승인된 신청을 취소하면 선점한 금액이 가용 예산으로 반환됩니다." : "신청이 삭제됩니다. 필요한 경우 다시 신청할 수 있어요."} confirmLabel="신청 취소" variant="danger" loading={canceling} onCancel={() => setConfirmCancel(false)} onConfirm={() => void handleCancel()} />
            <div className="mt-3 flex items-center justify-between gap-3">
                <StatusBadge status={request.status} />

                {request.rejectReason && (
                    <span className="text-xs text-red-500">
                        반려 사유: {request.rejectReason}
                    </span>
                )}
            </div>
        </div>
    );
}

function StatusBadge({
                         status,
                     }: {
    status: string;
}) {
    const statusMap: Record<
        string,
        {
            label: string;
            className: string;
        }
    > = {
        REQUEST: {
            label: "승인 대기",
            className: "bg-amber-50 text-amber-700",
        },
        APPROVE: {
            label: "승인",
            className: "bg-emerald-50 text-emerald-700",
        },
        REJECT: {
            label: "반려",
            className: "bg-red-50 text-red-700",
        },
        SETTLEMENT: {
            label: "정산 완료",
            className: "bg-zinc-100 text-zinc-600",
        },
    };

    const current = statusMap[status] ?? {
        label: status,
        className: "bg-zinc-100 text-zinc-600",
    };

    return (
        <span
            className={`rounded-full px-2.5 py-1 text-xs font-medium ${current.className}`}
        >
      {current.label}
    </span>
    );
}

function formatMoney(amount: number, currency: "KRW" | "USD" | "JPY") {
    const symbols = { KRW: "원", USD: "$", JPY: "¥" };

    const locales = { KRW: "ko-KR", USD: "en-US", JPY: "ja-JP" };
    const locale = locales[currency] || "ko-KR";

    const formattedAmount = amount.toLocaleString(locale);

    return currency === "KRW"
        ? `${formattedAmount}${symbols[currency]}`
        : `${symbols[currency]}${formattedAmount}`;
}

function formatDate(value: string) {
    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return date.toLocaleDateString("ko-KR");
}

