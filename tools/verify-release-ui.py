#!/usr/bin/env python3
"""Small device-UI release gate for the account/growth regression fixture."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


BOUNDS = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")
FORBIDDEN_ACCOUNT_COPY = (
    "昵称不可重复。合并前会明确显示来源",
    "同步前请在网络设置中绑定服务器",
    "仅在已绑定的家庭 Wi-Fi 且服务器可达时前台同步",
    "加入后将全量共享育儿记录",
)


def parse(path: Path) -> ET.Element:
    if not path.is_file():
        raise ValueError(f"UI hierarchy does not exist: {path}")
    return ET.parse(path).getroot()


def text_nodes(root: ET.Element, value: str) -> list[ET.Element]:
    return [node for node in root.iter() if node.attrib.get("text") == value]


def node_bounds(node: ET.Element) -> tuple[int, int, int, int]:
    match = BOUNDS.fullmatch(node.attrib.get("bounds", ""))
    if match is None:
        raise ValueError(f"Node has invalid bounds: {node.attrib}")
    return tuple(int(match.group(index)) for index in range(1, 5))


def node_height(node: ET.Element) -> int:
    _, top, _, bottom = node_bounds(node)
    return bottom - top


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--account-xml", type=Path, required=True)
    parser.add_argument("--account-scrolled-xml", type=Path)
    parser.add_argument("--growth-xml", type=Path, required=True)
    parser.add_argument("--baby-name", default="年年")
    args = parser.parse_args()

    account = parse(args.account_xml)
    account_scrolled = (
        parse(args.account_scrolled_xml) if args.account_scrolled_xml is not None else None
    )
    growth = parse(args.growth_xml)
    failures: list[str] = []

    account_roots = [account] + ([account_scrolled] if account_scrolled is not None else [])
    account_text = [
        node.attrib.get("text", "")
        for root in account_roots
        for node in root.iter()
    ]
    for copy in FORBIDDEN_ACCOUNT_COPY:
        if any(copy in value for value in account_text):
            failures.append(f"account still renders PRD-style copy: {copy}")

    # The fixture has one current-baby action and one profile-card action. When
    # the top row is squeezed, Compose drops the first label from the UI tree.
    if account_scrolled is None:
        edit_count = len(text_nodes(account, "编辑"))
        if edit_count < 2:
            failures.append(
                f"account edit actions are clipped: expected >=2 labels, found {edit_count}"
            )
    else:
        if not text_nodes(account, "编辑"):
            failures.append("account current-baby edit action is clipped on the first viewport")
        if not text_nodes(account_scrolled, "编辑"):
            failures.append("account profile-card edit action is clipped after scrolling")
        profile_labels = text_nodes(account_scrolled, f"{args.baby_name}（当前）")
        if not profile_labels:
            failures.append("account profile card cannot be reached after scrolling")

    brand_nodes = text_nodes(account, "乐记")
    baby_nodes = [
        node
        for node in text_nodes(growth, args.baby_name)
        if node_height(node) < 80
    ]
    if not brand_nodes or not baby_nodes:
        failures.append("could not locate both account and growth top-bar primary labels")
    else:
        brand = brand_nodes[0]
        baby = baby_nodes[0]
        brand_left, brand_top, _, brand_bottom = node_bounds(brand)
        baby_left, baby_top, _, baby_bottom = node_bounds(baby)
        brand_height = brand_bottom - brand_top
        baby_height = baby_bottom - baby_top
        if abs(brand_height - baby_height) > 4:
            failures.append(
                "top-bar primary labels use different visual sizes: "
                f"account={brand_height}px growth={baby_height}px"
            )
        if abs(brand_left - baby_left) > 8:
            failures.append(
                "top-bar primary labels are not horizontally aligned: "
                f"account={brand_left}px growth={baby_left}px"
            )
        brand_center = (brand_top + brand_bottom) // 2
        baby_center = (baby_top + baby_bottom) // 2
        if abs(brand_center - baby_center) > 8:
            failures.append(
                "top-bar primary labels are not vertically aligned: "
                f"account={brand_center}px growth={baby_center}px"
            )

    account_heroes = text_nodes(account, "账户")
    growth_heroes = text_nodes(growth, "成长")
    if not account_heroes or not growth_heroes:
        failures.append("could not locate account and growth page titles")
    else:
        account_hero = max(account_heroes, key=node_height)
        growth_hero = max(growth_heroes, key=node_height)
        account_left, _, _, _ = node_bounds(account_hero)
        growth_left, _, _, _ = node_bounds(growth_hero)
        if abs(node_height(account_hero) - node_height(growth_hero)) > 4:
            failures.append("account and growth page titles use different visual sizes")
        if abs(account_left - growth_left) > 4:
            failures.append(
                "account and growth page titles are not aligned: "
                f"account={account_left}px growth={growth_left}px"
            )

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print("release UI check: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
