import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { ServiceWorkerUpdater } from "@/components/ServiceWorkerUpdater";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "SwimPulse",
  description: "동네 수영장 등록 타이밍 알림 시스템",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="min-h-full flex flex-col">
        <ServiceWorkerUpdater />
        {children}
      </body>
    </html>
  );
}
