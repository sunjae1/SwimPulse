import { DashboardClient } from "@/components/DashboardClient";
import { getInitialDashboardData } from "@/lib/api";

type HomeProps = {
  searchParams?: Promise<{
    login?: string;
    notificationId?: string;
  }>;
};

export default async function Home({ searchParams }: HomeProps) {
  const initialData = await getInitialDashboardData();
  const resolvedSearchParams = searchParams ? await searchParams : undefined;

  return (
    <DashboardClient
      initialData={initialData}
      initialLoginSuccess={resolvedSearchParams?.login === "success"}
      initialLoginError={resolvedSearchParams?.login === "error"}
      initialNotificationId={resolvedSearchParams?.notificationId ?? null}
    />
  );
}
