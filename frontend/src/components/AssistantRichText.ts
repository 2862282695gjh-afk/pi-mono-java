import {
  Fragment,
  computed,
  defineComponent,
  h,
  onUnmounted,
  ref,
  watch,
  type PropType,
  type VNodeChild,
} from 'vue';
import {
  parseAssistantMarkdown,
  type RichElementNode,
  type RichTextNode,
} from '../markdown/richText';

export default defineComponent({
  name: 'AssistantRichText',
  props: {
    source: {
      type: String,
      required: true,
    },
    streaming: {
      type: Boolean as PropType<boolean>,
      default: false,
    },
  },
  setup(props) {
    const renderedSource = ref(props.source);
    const renderedStreaming = ref(props.streaming);
    const document = computed(() => parseAssistantMarkdown(renderedSource.value, renderedStreaming.value));
    const completionAnnouncement = ref('');
    let pendingSource = props.source;
    let renderFrame: number | undefined;
    let announcementTimer: number | undefined;

    watch(() => [props.source, props.streaming] as const, ([source, streaming], previous) => {
      if (!streaming) {
        cancelRenderFrame();
        renderedSource.value = source;
        renderedStreaming.value = false;
        if (previous?.[1]) announceCompletion();
        return;
      }
      pendingSource = source;
      renderedStreaming.value = true;
      scheduleRenderFrame();
    }, { flush: 'sync' });

    onUnmounted(() => {
      cancelRenderFrame();
      if (announcementTimer !== undefined) window.clearTimeout(announcementTimer);
    });

    function scheduleRenderFrame(): void {
      if (renderFrame !== undefined) return;
      renderFrame = window.requestAnimationFrame(() => {
        renderFrame = undefined;
        renderedSource.value = pendingSource;
      });
    }

    function cancelRenderFrame(): void {
      if (renderFrame === undefined) return;
      window.cancelAnimationFrame(renderFrame);
      renderFrame = undefined;
    }

    function announceCompletion(): void {
      completionAnnouncement.value = '回答已完成';
      if (announcementTimer !== undefined) window.clearTimeout(announcementTimer);
      announcementTimer = window.setTimeout(() => {
        completionAnnouncement.value = '';
      }, 2_000);
    }

    function renderNode(node: RichTextNode, path: string): VNodeChild {
      if (node.kind === 'text') return node.value;
      if (node.kind === 'inlineCode') return h('code', { class: 'rich-inline-code', key: path }, node.value);
      if (node.kind === 'streamingTail') {
        return h('span', { class: 'rich-streaming-tail', key: path }, node.value);
      }
      if (node.kind === 'imagePlaceholder') {
        const label = node.alt ? `图片已隐藏：${node.alt}` : '图片已隐藏';
        return h('span', { class: 'rich-image-placeholder', key: path }, label);
      }
      if (node.kind === 'codeBlock') return renderCodeBlock(node.value, node.language, path);
      if (node.kind === 'link') {
        const children = renderChildren(node.children, path);
        if (!node.href) return h('span', { class: 'rich-link-disabled', key: path }, children);
        const showHost = shouldShowLinkHost(node.children, node.host);
        return h(Fragment, { key: path }, [
          h('a', {
            class: 'rich-external-link',
            href: node.href,
            target: '_blank',
            rel: 'noopener noreferrer',
          }, [
            ...children,
            h('span', { class: 'rich-external-icon', 'aria-hidden': 'true' }, '↗'),
          ]),
          showHost
            ? h('span', { class: 'rich-link-host', 'aria-label': `链接域名 ${node.host}` }, ` (${node.host})`)
            : null,
        ]);
      }
      return renderElement(node, path);
    }

    function renderChildren(nodes: RichTextNode[], path: string): VNodeChild[] {
      return nodes.map((child, index) => renderNode(child, `${path}.${index}`));
    }

    function renderElement(node: RichElementNode, path: string): VNodeChild {
      if (node.name === 'table') {
        return h('div', {
          class: 'rich-table-scroll',
          tabindex: 0,
          role: 'region',
          'aria-label': '表格，可横向滚动',
          key: path,
        }, [h('table', renderChildren(node.children, path))]);
      }
      if (node.name === 'hr' || node.name === 'br') return h(node.name, { key: path });

      const attributes: Record<string, unknown> = { key: path };
      if (node.name === 'ol' && node.orderedStart) attributes.start = node.orderedStart;
      if ((node.name === 'th' || node.name === 'td') && node.textAlign) {
        attributes.style = { textAlign: node.textAlign };
      }
      if (node.name === 'li' && node.taskChecked !== undefined) {
        attributes.class = 'rich-task-item';
        return h('li', attributes, [
          h('input', {
            type: 'checkbox',
            checked: node.taskChecked,
            disabled: true,
            'aria-label': node.taskChecked ? '已完成' : '未完成',
          }),
          ...renderChildren(node.children, path),
        ]);
      }
      return h(node.name, attributes, renderChildren(node.children, path));
    }

    function renderCodeBlock(value: string, language: string, path: string): VNodeChild {
      return h('div', { class: 'rich-code-block', key: path }, [
        h('div', { class: 'rich-code-header' }, [
          h('span', language || 'text'),
        ]),
        h('pre', [h('code', value)]),
      ]);
    }

    function shouldShowLinkHost(nodes: RichTextNode[], host: string | null): boolean {
      if (!host) return false;
      const label = visibleText(nodes).trim().toLowerCase();
      return !label || !label.includes(host.toLowerCase());
    }

    function visibleText(nodes: RichTextNode[]): string {
      return nodes.map((node) => {
        if (node.kind === 'text' || node.kind === 'streamingTail') return node.value;
        if (node.kind === 'inlineCode' || node.kind === 'codeBlock') return node.value;
        if (node.kind === 'imagePlaceholder') return node.alt;
        if (node.kind === 'element' || node.kind === 'link') return visibleText(node.children);
        return '';
      }).join('');
    }

    return () => h('div', {
      class: ['assistant-rich-text', document.value.fallback && 'is-plain-fallback'],
      'aria-live': 'off',
    }, [
      document.value.fallback
        ? h('p', { class: 'rich-fallback-notice' }, '内容较长，已使用纯文本模式。')
        : null,
      ...document.value.nodes.map((node, index) => renderNode(node, String(index))),
      h('span', { class: 'sr-only', 'aria-live': 'polite' }, completionAnnouncement.value),
    ]);
  },
});
