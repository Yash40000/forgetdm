import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@xyflow/react/dist/style.css';
import './globals.css';

import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Inter, JetBrains_Mono } from 'next/font/google';
import { ColorSchemeScript, mantineHtmlProps } from '@mantine/core';
import { Providers } from '@/components/providers';
import { AppFrame } from '@/components/app-frame';

/* Load real fonts (the theme previously ASKED for Inter but nothing served it,
 * so everything fell back to Segoe UI). Inter for UI, JetBrains Mono for
 * technical values — the same pairing the best dev tools ship. */
const inter = Inter({ subsets: ['latin'], variable: '--font-inter', display: 'swap' });
const mono = JetBrains_Mono({ subsets: ['latin'], variable: '--font-mono', display: 'swap' });

export const metadata: Metadata = {
  title: 'ForgeTDM',
  description: 'Enterprise test data management'
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <html lang="en" {...mantineHtmlProps}>
      <head>
        <ColorSchemeScript defaultColorScheme="light" />
        <script
          dangerouslySetInnerHTML={{
            __html:
              "try{var t=localStorage.getItem('forgetdm.theme');if(['dark','light','midnight','azure','sage','slate','hc','soccer','soccer-pro'].indexOf(t)>-1){document.documentElement.dataset.forgeTheme=t;}}catch(e){}"
          }}
        />
      </head>
      <body className={`${inter.variable} ${mono.variable}`}>
        <Providers>
          <AppFrame>{children}</AppFrame>
        </Providers>
      </body>
    </html>
  );
}
