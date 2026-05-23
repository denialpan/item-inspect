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
    - uses the selected armature object's active action as the exported rig
    - samples every integer frame from Blender frame 1 through that last frame
    - samples evaluated dependency-graph matrices so pose constraints are included
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

EXPORT_POSE_BONE_NAMES = {"item_root", "viewmodel_arm_R"}


def evaluated_matrix_world(obj: bpy.types.Object):
    depsgraph = bpy.context.evaluated_depsgraph_get()
    return obj.evaluated_get(depsgraph).matrix_world.copy()


def matrix_from_named_source(name: str, source: str):
    obj = bpy.data.objects.get(source)
    if obj is not None:
        return evaluated_matrix_world(obj)

    armature = find_viewmodel_armature()
    if armature is not None and armature.pose and name in armature.pose.bones:
        return matrix_from_pose_bone(name, convert_to_minecraft=False)

    raise RuntimeError(f"Could not find export source object '{source}' or pose bone '{name}'.")


def matrix_from_pose_bone(name: str, convert_to_minecraft: bool = True):
    armature = find_viewmodel_armature()
    if armature is None:
        raise RuntimeError("Select the viewmodel armature in Object Mode before exporting.")
    if armature.pose is None or name not in armature.pose.bones:
        return None

    depsgraph = bpy.context.evaluated_depsgraph_get()
    armature_eval = armature.evaluated_get(depsgraph)
    matrix = armature_eval.matrix_world @ armature_eval.pose.bones[name].matrix
    if convert_to_minecraft:
        matrix = AUTHORING_TO_MINECRAFT_MATRIX @ matrix
    return matrix


def matrix_for_animation_sample(name: str, source: str):
    animation_source = ANIMATION_SOURCES.get(name)
    if animation_source is not None:
        obj = bpy.data.objects.get(animation_source)
        if obj is not None:
            return AUTHORING_TO_MINECRAFT_MATRIX @ evaluated_matrix_world(obj)

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
    start = 1
    end = None
    if "--" not in sys.argv:
        return start, end

    args = sys.argv[sys.argv.index("--") + 1 :]
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
        return any(f'pose.bones["{name}"]' in data_path for name in EXPORT_POSE_BONE_NAMES)

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

    return last


def find_viewmodel_armature() -> bpy.types.Object | None:
    selected_armatures = [obj for obj in bpy.context.selected_objects if obj.type == "ARMATURE"]
    if len(selected_armatures) > 1:
        names = ", ".join(obj.name for obj in selected_armatures)
        raise RuntimeError(f"Expected one selected armature, but found {len(selected_armatures)}: {names}")
    if not selected_armatures:
        return None

    return selected_armatures[0]


def detect_last_keyframe() -> int | None:
    armature = find_viewmodel_armature()
    if armature is None:
        raise RuntimeError("Select the viewmodel armature in Object Mode before exporting.")

    # Constraint influence keys live under pose.bones["viewmodel_arm_R"].
    # Only the active action on this armature defines the exported timeline;
    # stale/unassigned actions and helper-object keys are intentionally ignored.
    last = object_last_keyframe(armature, filter_pose_bones=True)
    if last is None:
        return None

    return int(math.ceil(last))


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
        print(f"Detected last keyframe {detected_end_frame}.")
        print(f"Exported animation frames {start_frame}..{end_frame}.")


if __name__ == "__main__":
    main()
