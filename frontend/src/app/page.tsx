import { DashboardClient } from "@/components/DashboardClient";
import { getInitialDashboardData } from "@/lib/api";

export default async function Home() {
  const initialData = await getInitialDashboardData();

  return <DashboardClient initialData={initialData} />;
}
