import { MyPageClient } from "@/components/MyPageClient";

type MyPageProps = {
  searchParams?: Promise<{ subscriptionId?: string; openDetail?: string }>;
};

export default async function MyPage({ searchParams }: MyPageProps) {
  const resolvedSearchParams = searchParams ? await searchParams : undefined;
  return (
    <MyPageClient
      initialSubscriptionId={resolvedSearchParams?.subscriptionId ?? null}
      initialOpenDetail={resolvedSearchParams?.openDetail === "1"}
    />
  );
}
