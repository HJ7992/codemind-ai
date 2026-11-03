from codemind_ai.domain.source import CodeChunk, Language, ParseStatus
from codemind_ai.parsing.models import SyntaxSpan


class SourceChunker:
    """按结构范围优先切块，超长区域使用有重叠的固定行窗口。"""

    def __init__(self, max_lines: int = 120, overlap_lines: int = 20) -> None:
        if max_lines < 1 or overlap_lines < 0 or overlap_lines >= max_lines:
            raise ValueError("切块行数必须大于 0，且重叠行数必须小于窗口行数")
        self.max_lines = max_lines
        self.overlap_lines = overlap_lines

    def chunk(
        self,
        text: str,
        spans: list[SyntaxSpan],
        project_id: str,
        source_version_id: str,
        relative_path: str,
        language: Language,
        parse_status: ParseStatus,
    ) -> tuple[CodeChunk, ...]:
        lines = text.splitlines(keepends=True)
        if not lines:
            return ()
        if len(lines) <= self.max_lines:
            ranges = [(1, len(lines))]
        else:
            ranges = self._chunk_region(1, len(lines), spans)
            ranges.sort()
        return tuple(
            CodeChunk.create(
                project_id=project_id,
                source_version_id=source_version_id,
                relative_path=relative_path,
                language=language,
                start_line=start,
                end_line=end,
                text="".join(lines[start - 1 : end]),
                parse_status=parse_status,
            )
            for start, end in ranges
        )

    def _chunk_region(
        self, start: int, end: int, children: list[SyntaxSpan]
    ) -> list[tuple[int, int]]:
        if end - start + 1 <= self.max_lines:
            return [(start, end)]
        ranges: list[tuple[int, int]] = []
        cursor = start
        for child in sorted(children, key=lambda item: item.start_line):
            child_start = max(start, child.start_line)
            child_end = min(end, child.end_line)
            if child_start > child_end or child_end < cursor:
                continue
            if cursor < child_start:
                ranges.extend(self._fixed_windows(cursor, child_start - 1))
            ranges.extend(self._chunk_region(child_start, child_end, list(child.children)))
            cursor = child_end + 1
        if cursor <= end:
            ranges.extend(self._fixed_windows(cursor, end))
        return ranges

    def _fixed_windows(self, start: int, end: int) -> list[tuple[int, int]]:
        ranges: list[tuple[int, int]] = []
        current = start
        while current <= end:
            window_end = min(current + self.max_lines - 1, end)
            ranges.append((current, window_end))
            if window_end == end:
                break
            # 下一步只回退 overlap_lines，保证窗口始终向前推进。
            current = window_end - self.overlap_lines + 1
        return ranges
