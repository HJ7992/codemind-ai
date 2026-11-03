import tree_sitter_java
from tree_sitter import Language, Node, Parser

from codemind_ai.domain.source import ParseStatus
from codemind_ai.parsing.models import SyntaxSpan

_TYPE_NODES = {
    "class_declaration",
    "interface_declaration",
    "enum_declaration",
    "record_declaration",
}
_METHOD_NODES = {"method_declaration", "constructor_declaration"}
_BODY_NODES = {"class_body", "interface_body", "enum_body", "record_body"}


class JavaStructureParser:
    def parse(self, text: str) -> tuple[ParseStatus, list[SyntaxSpan]]:
        parser = Parser(Language(tree_sitter_java.language()))
        tree = parser.parse(text.encode("utf-8"))
        if tree.root_node.has_error:
            return ParseStatus.FALLBACK, []
        spans = [
            self._span(node) for node in tree.root_node.named_children if node.type in _TYPE_NODES
        ]
        return ParseStatus.PARSED, spans

    def _span(self, node: Node) -> SyntaxSpan:
        end_line = node.end_point[0] if node.end_point[1] == 0 else node.end_point[0] + 1
        children: list[SyntaxSpan] = []
        for body in (child for child in node.named_children if child.type in _BODY_NODES):
            children.extend(
                self._span(child) for child in body.named_children if child.type in _METHOD_NODES
            )
        return SyntaxSpan(
            start_line=node.start_point[0] + 1,
            end_line=end_line,
            children=tuple(children),
        )
