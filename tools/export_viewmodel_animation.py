"""
Export the current Blender viewmodel pose and baked animation samples into the
Minecraft resource loaded by the iteminspect client renderer.

Run from Blender with the generated .blend open:
    blender minecraft_1_21_1_empty_hand_viewmodel.blend --background --python tools/export_viewmodel_pose.py

Output:
    src/main/resources/assets/iteminspect/viewmodel/default_pose.json

The exporter writes decomposed TRS data. The Minecraft renderer applies each
transform as translate -> quaternion rotation -> scale.

Animation export:
    - auto-detects the last keyframed frame in the scene/action data
    - samples every integer frame from scene.frame_start through that last frame
    - prefers animated pose bones for frame samples
    - keeps the static "bones" pose from the hidden Minecraft-space export anchors
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import bpy
from mathutils import Matrix


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "src/main/resources/assets/iteminspect/viewmodel/default_pose.json"
DEFAULT_ANIMATION_NAME = "default"
AUTHORING_TO_MINECRAFT_MATRIX = Matrix.Rotation(math.radians(-90.0), 4, "X")

EXPORT_SOURCES = {
    "item_root": "held_item_transform_anchor_firstperson_righthand",
    "block_root": "held_block_transform_anchor_firstperson_righthand",
    "viewmodel_arm_R": "viewmodel_arm_R_transform_anchor",
}

ANIMATION_SOURCES = {
    "item_root": "held_item_transform_anchor_firstperson_righthand_display",
    "block_root": "held_block_transform_anchor_firstperson_righthand_display",
    "viewmodel_arm_R": "viewmodel_arm_R_transform_anchor_display",
}

BONE_TO_EXPORT_SOURCE = {bone: source for bone, source in EXPORT_SOURCES.items()}


def matrix_from_named_source(name: str, source: str):
    obj = bpy.data.objects.get(source)
    if obj is not None:
        return obj.matrix_world.copy()

    armature = bpy.data.objects.get("viewmodel_armature")
    if armature is not None and armature.pose and name in armature.pose.bones:
        return armature.matrix_world @ armature.pose.bones[name].matrix

    raise RuntimeError(f"Could not find export source object '{source}' or pose bone '{name}'.")


def matrix_from_pose_bone(name: str):
    armature = bpy.data.objects.get("viewmodel_armature")
    if armature is None or armature.pose is None or name not in armature.pose.bones:
        return None

    return AUTHORING_TO_MINECRAFT_MATRIX @ armature.matrix_world @ armature.pose.bones[name].matrix


def matrix_for_animation_sample(name: str, source: str):
    animation_source = ANIMATION_SOURCES.get(name)
    if animation_source is not None:
        obj = bpy.data.objects.get(animation_source)
        if obj is not None:
            return AUTHORING_TO_MINECRAFT_MATRIX @ obj.matrix_world

    pose_matrix = matrix_from_pose_bone(name)
    if pose_matrix is not None:
        return pose_matrix

    return matrix_from_named_source(name, source)


def trs_from_matrix(matrix):
    translation, rotation, scale = matrix.decompose()
    return {
        "translation": [round(translation.x, 8), round(translation.y, 8), round(translation.z, 8)],
        "rotation": [round(rotation.x, 8), round(rotation.y, 8), round(rotation.z, 8), round(rotation.w, 8)],
        "scale": [round(scale.x, 8), round(scale.y, 8), round(scale.z, 8)],
    }


def frame_range_from_args() -> tuple[int, int | None]:
    start = bpy.context.scene.frame_start
    end = None
    if "--" not in sys.argv:
        return start, end

    args = sys.argv[sys.argv.index("--") + 1 :]
    if "--start" in args:
        index = args.index("--start")
        if index + 1 >= len(args):
            raise RuntimeError("--start requires a frame number.")
        start = int(args[index + 1])
    if "--end" in args:
        index = args.index("--end")
        if index + 1 >= len(args):
            raise RuntimeError("--end requires a frame number.")
        end = int(args[index + 1])

    return start, end


def animation_name_from_args() -> str:
    if "--" not in sys.argv:
        return DEFAULT_ANIMATION_NAME

    args = sys.argv[sys.argv.index("--") + 1 :]
    if "--animation" in args:
        index = args.index("--animation")
        if index + 1 >= len(args):
            raise RuntimeError("--animation requires a name.")
        return args[index + 1]

    return DEFAULT_ANIMATION_NAME


def keyed_id_matches_export_bone(data_path: str) -> bool:
    if not data_path:
        return False

    if data_path.startswith("pose.bones["):
        return any(name in data_path for name in BONE_TO_EXPORT_SOURCE)

    return False


def action_last_keyframe(action, filter_pose_bones: bool) -> float | None:
    last = None
    for fcurve in action.fcurves:
        if filter_pose_bones and not keyed_id_matches_export_bone(fcurve.data_path):
            continue

        for point in fcurve.keyframe_points:
            frame = point.co.x
            last = frame if last is None else max(last, frame)

    return last


def object_last_keyframe(obj, filter_pose_bones: bool = False) -> float | None:
    animation_data = obj.animation_data
    if animation_data is None:
        return None

    last = None
    if animation_data.action is not None:
        last = action_last_keyframe(animation_data.action, filter_pose_bones)

    if animation_data.nla_tracks:
        for track in animation_data.nla_tracks:
            for strip in track.strips:
                last = strip.frame_end if last is None else max(last, strip.frame_end)

    return last


def detect_last_keyframe() -> int | None:
    candidates: list[float] = []

    armature = bpy.data.objects.get("viewmodel_armature")
    if armature is not None:
        last = object_last_keyframe(armature, filter_pose_bones=True)
        if last is not None:
            candidates.append(last)

    for source in set(EXPORT_SOURCES.values()) | set(ANIMATION_SOURCES.values()):
        obj = bpy.data.objects.get(source)
        if obj is None:
            continue
        last = object_last_keyframe(obj)
        if last is not None:
            candidates.append(last)

    if not candidates:
        return None

    return int(math.ceil(max(candidates)))


def export_animation(start_frame: int, end_frame: int):
    scene = bpy.context.scene
    original_frame = scene.frame_current
    frames = []

    try:
        for frame in range(start_frame, end_frame + 1):
            scene.frame_set(frame)
            bpy.context.view_layer.update()
            frames.append(
                {
                    "frame": frame,
                    "bones": {
                        name: trs_from_matrix(matrix_for_animation_sample(name, source))
                        for name, source in EXPORT_SOURCES.items()
                    },
                }
            )
    finally:
        scene.frame_set(original_frame)
        bpy.context.view_layer.update()

    return frames


def output_path_from_args() -> Path:
    if "--" not in sys.argv:
        return DEFAULT_OUTPUT

    args = sys.argv[sys.argv.index("--") + 1 :]
    if "--output" in args:
        index = args.index("--output")
        if index + 1 >= len(args):
            raise RuntimeError("--output requires a path.")
        return Path(args[index + 1]).resolve()

    return DEFAULT_OUTPUT


def main() -> None:
    output = output_path_from_args()
    start_frame, requested_end_frame = frame_range_from_args()
    detected_end_frame = detect_last_keyframe()
    end_frame = requested_end_frame if requested_end_frame is not None else detected_end_frame

    bones = {
        name: trs_from_matrix(matrix_from_named_source(name, source))
        for name, source in EXPORT_SOURCES.items()
    }
    data = {
        "format": 2,
        "coordinate_space": "minecraft_first_person_render",
        "bones": bones,
    }

    if end_frame is not None and end_frame >= start_frame:
        animation_name = animation_name_from_args()
        data["animations"] = {
            animation_name: {
                "fps": bpy.context.scene.render.fps,
                "start_frame": start_frame,
                "end_frame": end_frame,
                "length_frames": end_frame - start_frame + 1,
                "loop": True,
                "frames": export_animation(start_frame, end_frame),
            }
        }

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
    print(f"Exported viewmodel pose to {output}")
    if end_frame is None:
        print("No keyed animation data detected; exported static pose only.")
    else:
        print(f"Exported animation frames {start_frame}..{end_frame}.")


if __name__ == "__main__":
    main()
