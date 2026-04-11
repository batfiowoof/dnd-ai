import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "D&D AI",
  description: "AI Dungeon Master - Dungeons & Dragons",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="min-h-screen bg-bg text-text antialiased">
        {children}
      </body>
    </html>
  );
}
