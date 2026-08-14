import type { Metadata } from 'next';
import { Providers } from './providers';
import '@nubase/ui/styles.css';
import './globals.css';

export const metadata: Metadata = {
  title: 'Nubase Studio',
  description: '管理你的 nubase 项目。',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body className="min-h-screen bg-background font-sans antialiased">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
