"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const navigationItems = [
  { href: "/", label: "대시보드" },
  { href: "/my-page", label: "마이 페이지" },
  { href: "/admin", label: "관리자" },
];

export function AppNavigation() {
  const pathname = usePathname();

  return (
    <nav aria-label="주요 메뉴" className="flex flex-wrap items-center gap-2">
      {navigationItems.map((item) => {
        const active = item.href === "/"
          ? pathname === item.href
          : pathname === item.href || pathname.startsWith(`${item.href}/`);

        return (
          <Link
            key={item.href}
            href={item.href}
            aria-current={active ? "page" : undefined}
            className={`inline-flex h-10 items-center justify-center rounded-full border px-4 text-sm font-semibold transition ${
              active
                ? "border-[#075985] bg-[#075985] text-white"
                : "border-[#c8def0] bg-white text-[#28516f] hover:border-[#0284c7] hover:text-[#0369a1]"
            }`}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
