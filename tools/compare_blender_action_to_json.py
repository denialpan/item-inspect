"""
Compare a Blender action against an exported viewmodel JSON.

Run from Blender, for example:
    blender "D:\\daniel\\video editing\\blender projects\\iteminspect\\iteminspect_animations.blend" --background --python tools/compare_blender_action_to_json.py -- --action default_block_both_inspect --json src/main/resources/assets/iteminspect/viewmodel/default/default_block_both_inspect.json
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
from pathlib import Path

import bpy


ROOT = Path(__file__).resolve().parents[1]
EXPORTER_PATH = ROOT / "tools" / "export_viewmodel_animation.py"
DEFAULT_JSON = ROOT / "src/main/resources/assets/iteminspect/viewmodel/default/default_block_both_inspect.json"
DEFAULT_ACTION = "default_block_both_inspect"
COMPARE_BONES = ("item_offhand_root", "viewmodel_arm_L")
COMPARE_FRAMES = (1, 2, 10, 30, 60, 90, 120, 121, 130, 150, 180, 240, 300, 340)


def load_exporter():
    spec = importlib.util.spec_from_file_location("viewmodel_exporter", EXPORTER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load exporter from {EXPORTER_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_args():
    argv = []
    if "--" in __import__("sys").argv:
        argv = __import__("sys").argv[__import__("sys").argv.index("--") + 1 :]

    parser = argparse.ArgumentParser()
    parser.add_argument("--action", default=DEFAULT_ACTION)
    parser.add_argument("--json", type=Path, default=DEFAULT_JSON)
    parser.add_argument("--frames", nargs="*", type=int, default=list(COMPARE_FRAMES))
    return parser.parse_args(argv)


def vector_delta(left, right) -> float:
    return math.sqrt(sum((float(a) - float(b)) ** 2 for a, b in zip(left, right)))


def trs_delta(left: dict, right: dict) -> tuple[float, float, float]:
    return (
        vector_delta(left["translation"], right["translation"]),
        vector_delta(left["rotation"], right["rotation"]),
        vector_delta(left["scale"], right["scale"]),
    )


def active_armature():
    selected = [obj for obj in bpy.context.selected_objects if obj.type == "ARMATURE"]
    if selected:
        return selected[0]
    armatures = [obj for obj in bpy.context.scene.objects if obj.type == "ARMATURE"]
    if len(armatures) == 1:
        bpy.ops.object.select_all(action="DESELECT")
        armatures[0].select_set(True)
        bpy.context.view_layer.objects.active = armatures[0]
        return armatures[0]
    names = ", ".join(obj.name for obj in armatures)
    raise RuntimeError(f"Select one armature before running comparison. Found: {names}")


def main() -> None:
    args = parse_args()
    exporter = load_exporter()
    armature = active_armature()
    action = exporter.set_armature_action(armature, args.action)

    exported = json.loads(args.json.read_text(encoding="utf-8"))
    animation_json = exported["animations"][args.action]
    json_frames = {frame["frame"]: frame["bones"] for frame in animation_json["frames"]}

    print(f"Selected armature: {armature.name}")
    print(f"Action: {action.name}")
    print(f"JSON: {args.json}")
    print("Exporter animation sources:")
    for bone in COMPARE_BONES:
        print(f"  {bone}: {exporter.ANIMATION_SOURCES.get(bone)!r}")

    original_frame = bpy.context.scene.frame_current
    try:
        for frame in args.frames:
            if frame not in json_frames:
                continue
            bpy.context.scene.frame_set(frame)
            bpy.context.view_layer.update()
            print(f"\nFrame {frame}")
            for bone in COMPARE_BONES:
                sampled_matrix = exporter.matrix_for_animation_sample(bone, exporter.EXPORT_SOURCES[bone])
                sampled = exporter.trs_from_matrix(sampled_matrix)
                current_json = json_frames[frame][bone]
                dt, dr, ds = trs_delta(sampled, current_json)
                print(
                    f"  {bone}: delta translation={dt:.8f}, rotation={dr:.8f}, scale={ds:.8f}; "
                    f"sampled_t={sampled['translation']} json_t={current_json['translation']}"
                )
                raw_bone_matrix = exporter.matrix_from_pose_bone(bone)
                if raw_bone_matrix is not None:
                    raw = exporter.trs_from_matrix(raw_bone_matrix)
                    bt, br, bs = trs_delta(raw, sampled)
                    print(
                        f"    raw pose bone vs sampled source: translation={bt:.8f}, "
                        f"rotation={br:.8f}, scale={bs:.8f}; raw_t={raw['translation']}"
                    )
    finally:
        bpy.context.scene.frame_set(original_frame)
        bpy.context.view_layer.update()


if __name__ == "__main__":
    main()
