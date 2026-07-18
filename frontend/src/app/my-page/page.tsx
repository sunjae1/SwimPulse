import { MyPageClient } from "@/components/MyPageClient";

type MyPageProps = {
  searchParams?: Promise<{ subscriptionId?: string }>;
};

export default async function MyPage({ searchParams }: MyPageProps) {
  const resolvedSearchParams = searchParams ? await searchParams : undefined;
  return <MyPageClient initialSubscriptionId={resolvedSearchParams?.subscriptionId ?? null} />;
}
