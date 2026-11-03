from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class SyntaxSpan:
    """解析器给出的结构范围，行号均从 1 开始且包含结束行。"""

    start_line: int
    end_line: int
    children: tuple["SyntaxSpan", ...] = field(default_factory=tuple)

    @property
    def line_count(self) -> int:
        return self.end_line - self.start_line + 1
