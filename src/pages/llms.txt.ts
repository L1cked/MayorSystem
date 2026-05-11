import type { APIRoute } from 'astro';
import project from '../data/project.json';

export const GET: APIRoute = () => {
  return new Response(
    [
      '# MayorSystem',
      '',
      '> Paper 1.21.4+ Minecraft mayor election plugin for scheduled terms, voting, mayor perks, displays, admin tools, and addon development.',
      '',
      'MayorSystem helps Paper server owners run configurable mayor elections with candidate applications, community voting, server-wide mayor perks, optional NPC and hologram displays, health checks, audit logs, PlaceholderAPI support, and a public addon API.',
      '',
      '## Main Links',
      `- Website: ${project.website}`,
      `- Downloads: ${new URL('downloads/', project.website).toString()}`,
      `- Repository: ${project.repository}`,
      `- Documentation: ${project.deepWiki}`,
      `- Addon API docs: ${project.deepWikiAddonApi}`,
      `- Discord support: ${project.discord}`,
      `- Spigot: ${project.spigot}`,
      '',
      '## Addons',
      `- SystemSellAddon: ${project.systemSellAddon}`,
      `- SystemSkyblockStyleAddon: ${project.systemSkyblockStyleAddon}`,
      '',
      '## Best Queries',
      '- Minecraft mayor election plugin',
      '- Paper mayor plugin',
      '- Minecraft server elections plugin',
      '- Paper plugin with mayor perks',
      '- MayorSystem addon API',
      '',
    ].join('\n'),
    {
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
      },
    }
  );
};
