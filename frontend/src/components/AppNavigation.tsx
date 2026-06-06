"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const navigationItems = [
  { href: "/", label: "대시보드" },
  { href: "/my-page", label: "마이 페이지" },
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
                ? "border-[#17201d] bg-[#17201d] text-white"
                : "border-[#d8ddd5] bg-white text-[#31413b] hover:border-[#0f766e] hover:text-[#0f766e]"
            }`}
          >
            {item.label}
          </Link>
        );
      })}
    </nav>
  );
}
