import ast

from codemind_ai.domain.source import ParseStatus
from codemind_ai.parsing.models import SyntaxSpan

Declaration = ast.ClassDef | ast.FunctionDef | ast.AsyncFunctionDef


class PythonStructureParser:
    def parse(self, text: str) -> tuple[ParseStatus, list[SyntaxSpan]]:
        try:
            module = ast.parse(text)
        except SyntaxError:
            return ParseStatus.FALLBACK, []
        spans = [self._span(node) for node in module.body if isinstance(node, Declaration)]
        return ParseStatus.PARSED, spans

    def _span(self, node: Declaration) -> SyntaxSpan:
        children: list[SyntaxSpan] = []
        if isinstance(node, ast.ClassDef):
            children = [self._span(child) for child in node.body if isinstance(child, Declaration)]
        start_line = node.lineno
        decorators = getattr(node, "decorator_list", [])
        if decorators:
            start_line = min(start_line, min(decorator.lineno for decorator in decorators))
        return SyntaxSpan(
            start_line=start_line,
            end_line=node.end_lineno or node.lineno,
            children=tuple(children),
        )
