import type { APIRoute } from 'astro';
import project from '../data/project.json';

const urls = [
  project.website,
  new URL('downloads/', project.website).toString(),
  new URL('llms.txt', project.website).toString(),
];

export const GET: APIRoute = () => {
  const entries = urls
    .map((url) => `  <url><loc>${url}</loc></url>`)
    .join('\n');

  return new Response(
    [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
      entries,
      '</urlset>',
      '',
    ].join('\n'),
    {
      headers: {
        'Content-Type': 'application/xml; charset=utf-8',
      },
    }
  );
};

