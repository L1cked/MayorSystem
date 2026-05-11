import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

export default defineConfig({
  site: 'https://l1cked.github.io',
  base: '/MayorSystem',
  integrations: [
    sitemap(),
  ],
});
