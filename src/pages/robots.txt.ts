import type { APIRoute } from 'astro';

export const GET: APIRoute = ({ site }) => {
  const origin = site?.toString().replace(/\/$/, '') ?? 'https://l1cked.github.io';
  return new Response(
    [
      'User-agent: *',
      'Allow: /',
      '',
      `Sitemap: ${origin}/MayorSystem/sitemap.xml`,
      '',
    ].join('\n'),
    {
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
      },
    }
  );
};
