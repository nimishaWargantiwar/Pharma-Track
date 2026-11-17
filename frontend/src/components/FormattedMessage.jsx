import React from 'react';

/**
 * Pure, safe Markdown and mathematical expression renderer for PharmaTrack AI Copilot messages.
 *
 * <p>Security & Safety Guarantees:
 * <ul>
 *   <li>NEVER uses dangerouslySetInnerHTML or innerHTML under any circumstance.</li>
 *   <li>Parses text strictly into native React element trees (h1-h4, p, ul, ol, li, table, pre, code).</li>
 *   <li>All dynamic text values are rendered as standard React text nodes with automatic escaping.</li>
 *   <li>Raw HTML tags in model output (e.g., &lt;script&gt;) are rendered as escaped plain text strings.</li>
 * </ul>
 */

const INLINE_REGEX = /(`[^`]+`|\*\*\*[^*]+\*\*\*|\*\*[^*]+\*\*|\*[^*]+\*|\$[^$]+\$)/g;

export function renderInlineContent(text) {
  if (text == null) {
    return null;
  }
  const str = String(text);
  if (!str) {
    return null;
  }

  const parts = [];
  let lastIndex = 0;
  let match;

  while ((match = INLINE_REGEX.exec(str)) !== null) {
    if (match.index > lastIndex) {
      parts.push(str.substring(lastIndex, match.index));
    }

    const token = match[0];
    const key = `inline_${match.index}_${token.length}`;

    if (token.startsWith('`') && token.endsWith('`')) {
      parts.push(
        <code
          key={key}
          className="px-1.5 py-0.5 rounded bg-slate-200/80 text-pharma-900 font-mono text-[11px] font-semibold border border-slate-300/70"
        >
          {token.slice(1, -1)}
        </code>
      );
    } else if (token.startsWith('***') && token.endsWith('***')) {
      parts.push(
        <strong key={key} className="font-semibold text-slate-900">
          <em className="italic">{token.slice(3, -3)}</em>
        </strong>
      );
    } else if (token.startsWith('**') && token.endsWith('**')) {
      parts.push(
        <strong key={key} className="font-semibold text-slate-900">
          {token.slice(2, -2)}
        </strong>
      );
    } else if (token.startsWith('*') && token.endsWith('*')) {
      parts.push(
        <em key={key} className="italic text-slate-800">
          {token.slice(1, -1)}
        </em>
      );
    } else if (token.startsWith('$') && token.endsWith('$')) {
      parts.push(
        <span
          key={key}
          data-testid="math-expr"
          className="inline-flex items-center px-1.5 py-0.2 rounded font-mono text-[11px] font-medium bg-amber-50 text-amber-900 border border-amber-200/80"
        >
          {token.slice(1, -1)}
        </span>
      );
    }

    lastIndex = INLINE_REGEX.lastIndex;
  }

  if (lastIndex < str.length) {
    parts.push(str.substring(lastIndex));
  }

  return parts;
}

function isTableStart(lines, index) {
  if (index + 1 >= lines.length) return false;
  const current = lines[index].trim();
  const next = lines[index + 1].trim();
  return (
    current.startsWith('|') &&
    current.endsWith('|') &&
    next.startsWith('|') &&
    next.includes('-') &&
    next.endsWith('|')
  );
}

export function parseMarkdownBlocks(markdown) {
  if (markdown == null) {
    return [];
  }
  // Pre-normalize inline numbered list items run together on a single line (e.g. "1. First. 2. Second. 3. Third.")
  const normalizedMarkdown = String(markdown).replace(/(?<=[.!?])\s+(?=[1-9]\d*\.\s+)/g, '\n');
  const lines = normalizedMarkdown.split(/\r?\n/);
  const blocks = [];
  let i = 0;

  while (i < lines.length) {
    const rawLine = lines[i];
    const trimmedLine = rawLine.trim();

    // 1. Fenced Code Block (```lang ... ```)
    if (trimmedLine.startsWith('```')) {
      const language = trimmedLine.slice(3).trim();
      const codeLines = [];
      i++;
      while (i < lines.length && !lines[i].trim().startsWith('```')) {
        codeLines.push(lines[i]);
        i++;
      }
      i++; // skip closing ```
      blocks.push({
        type: 'codeblock',
        language,
        code: codeLines.join('\n'),
      });
      continue;
    }

    // 2. Blank line
    if (trimmedLine === '') {
      i++;
      continue;
    }

    // 3. Headings (# to ####)
    const headingMatch = rawLine.match(/^(#{1,4})\s+(.+)$/);
    if (headingMatch) {
      blocks.push({
        type: 'heading',
        level: headingMatch[1].length,
        content: headingMatch[2].trim(),
      });
      i++;
      continue;
    }

    // 4. Tables (| col 1 | col 2 |)
    if (isTableStart(lines, i)) {
      const parseRow = (row) =>
        row
          .trim()
          .slice(1, -1)
          .split('|')
          .map((cell) => cell.trim());

      const headers = parseRow(trimmedLine);
      i += 2; // skip header and delimiter rows
      const rows = [];
      while (i < lines.length && lines[i].trim().startsWith('|') && lines[i].trim().endsWith('|')) {
        rows.push(parseRow(lines[i]));
        i++;
      }
      blocks.push({
        type: 'table',
        headers,
        rows,
      });
      continue;
    }

    // 5. Unordered Lists (- or *)
    if (/^[\*\-]\s+/.test(trimmedLine)) {
      const items = [];
      while (i < lines.length) {
        const lineTrim = lines[i].trim();
        if (/^[\*\-]\s+/.test(lineTrim)) {
          items.push(lineTrim.replace(/^[\*\-]\s+/, ''));
          i++;
        } else if (lineTrim === '' && i + 1 < lines.length && /^[\*\-]\s+/.test(lines[i + 1].trim())) {
          i++;
        } else {
          break;
        }
      }
      blocks.push({
        type: 'unordered-list',
        items,
      });
      continue;
    }

    // 6. Ordered Lists (1. 2. )
    if (/^[1-9]\d*\.\s+/.test(trimmedLine)) {
      const items = [];
      while (i < lines.length) {
        const lineTrim = lines[i].trim();
        if (/^[1-9]\d*\.\s+/.test(lineTrim)) {
          items.push(lineTrim.replace(/^[1-9]\d*\.\s+/, ''));
          i++;
        } else if (lineTrim === '' && i + 1 < lines.length && /^[1-9]\d*\.\s+/.test(lines[i + 1].trim())) {
          i++;
        } else {
          break;
        }
      }
      blocks.push({
        type: 'ordered-list',
        items,
      });
      continue;
    }

    // 7. Blockquotes (> ...)
    if (trimmedLine.startsWith('>')) {
      const quoteLines = [];
      while (i < lines.length && lines[i].trim().startsWith('>')) {
        quoteLines.push(lines[i].trim().replace(/^>\s*/, ''));
        i++;
      }
      blocks.push({
        type: 'blockquote',
        content: quoteLines.join(' '),
      });
      continue;
    }

    // 8. Standard Paragraph
    const paraLines = [];
    while (
      i < lines.length &&
      lines[i].trim() !== '' &&
      !lines[i].trim().startsWith('```') &&
      !lines[i].match(/^(#{1,4})\s+(.+)$/) &&
      !isTableStart(lines, i) &&
      !/^[\*\-]\s+/.test(lines[i].trim()) &&
      !/^[1-9]\d*\.\s+/.test(lines[i].trim()) &&
      !lines[i].trim().startsWith('>')
    ) {
      paraLines.push(lines[i]);
      i++;
    }
    if (paraLines.length === 0 && i < lines.length) {
      paraLines.push(lines[i]);
      i++;
    }
    if (paraLines.length > 0) {
      blocks.push({
        type: 'paragraph',
        content: paraLines.join(' '),
      });
    }
  }

  return blocks;
}

export default function FormattedMessage({ content, text }) {
  const messageContent = content || text;
  if (!messageContent) {
    return null;
  }

  const blocks = parseMarkdownBlocks(messageContent);

  return (
    <div className="space-y-2 text-xs leading-relaxed text-slate-800">
      {blocks.map((block, idx) => {
        const key = `block_${idx}_${block.type}`;

        switch (block.type) {
          case 'heading': {
            if (block.level === 1) {
              return (
                <h1 key={key} className="text-sm font-bold text-slate-900 mt-2.5 mb-1 first:mt-0">
                  {renderInlineContent(block.content)}
                </h1>
              );
            }
            if (block.level === 2) {
              return (
                <h2 key={key} className="text-xs font-bold text-slate-900 uppercase tracking-wide mt-2 mb-1 first:mt-0">
                  {renderInlineContent(block.content)}
                </h2>
              );
            }
            return (
              <h3 key={key} className="text-xs font-semibold text-slate-800 mt-1.5 mb-0.5 first:mt-0">
                {renderInlineContent(block.content)}
              </h3>
            );
          }

          case 'paragraph': {
            return (
              <p key={key} className="mb-1.5 last:mb-0 leading-relaxed">
                {renderInlineContent(block.content)}
              </p>
            );
          }

          case 'unordered-list': {
            return (
              <ul key={key} className="my-1.5 pl-4 list-disc space-y-1 text-slate-700">
                {block.items.map((item, itemIdx) => (
                  <li key={`li_${idx}_${itemIdx}`} className="leading-relaxed">
                    {renderInlineContent(item)}
                  </li>
                ))}
              </ul>
            );
          }

          case 'ordered-list': {
            return (
              <ol key={key} className="my-1.5 pl-4 list-decimal space-y-1 text-slate-700">
                {block.items.map((item, itemIdx) => (
                  <li key={`oli_${idx}_${itemIdx}`} className="leading-relaxed">
                    {renderInlineContent(item)}
                  </li>
                ))}
              </ol>
            );
          }

          case 'table': {
            return (
              <div key={key} className="my-2 overflow-x-auto rounded-lg border border-slate-200 shadow-2xs">
                <table className="min-w-full divide-y divide-slate-200 text-xs">
                  {block.headers.length > 0 && (
                    <thead className="bg-slate-100/90 text-slate-700 font-semibold text-[11px] uppercase tracking-wider">
                      <tr>
                        {block.headers.map((hdr, hIdx) => (
                          <th key={`th_${idx}_${hIdx}`} className="px-3 py-1.5 text-left font-semibold">
                            {renderInlineContent(hdr)}
                          </th>
                        ))}
                      </tr>
                    </thead>
                  )}
                  <tbody className="divide-y divide-slate-100 bg-white">
                    {block.rows.map((row, rIdx) => (
                      <tr key={`tr_${idx}_${rIdx}`} className="hover:bg-slate-50/70 transition-colors">
                        {row.map((cell, cIdx) => (
                          <td key={`td_${idx}_${rIdx}_${cIdx}`} className="px-3 py-1.5 text-slate-700 whitespace-nowrap">
                            {renderInlineContent(cell)}
                          </td>
                        ))}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            );
          }

          case 'blockquote': {
            return (
              <blockquote
                key={key}
                data-testid="copilot-blockquote"
                className="my-2 p-3 bg-amber-50/80 border-l-4 border-amber-500 rounded-r-lg text-amber-900 text-xs italic leading-relaxed"
              >
                {renderInlineContent(block.content)}
              </blockquote>
            );
          }

          case 'codeblock': {
            return (
              <div key={key} className="my-2 rounded-lg bg-slate-900 text-slate-100 p-3 font-mono text-xs overflow-x-auto shadow-inner">
                {block.language && (
                  <div className="text-[10px] text-slate-400 uppercase tracking-widest font-semibold mb-1 pb-1 border-b border-slate-800">
                    {block.language}
                  </div>
                )}
                <pre className="m-0 font-mono text-[11px] leading-relaxed whitespace-pre">
                  <code>{block.code}</code>
                </pre>
              </div>
            );
          }

          default:
            return null;
        }
      })}
    </div>
  );
}
