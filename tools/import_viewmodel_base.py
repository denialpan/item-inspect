"""
Create a Blender scene for the vanilla Minecraft 1.21.1 first-person empty-hand
viewmodel arm and neutral held-item/block animation anchors.

Run from Blender:
    blender --background --python tools/create_minecraft_viewmodel_blend.py

Output:
    tools/minecraft_1_21_1_empty_hand_viewmodel.blend

The transform constants are copied from the NeoForm-transformed Minecraft
sources in this template:
    net.minecraft.client.renderer.ItemInHandRenderer#renderPlayerArm
    net.minecraft.client.renderer.ItemInHandRenderer#applyItemArmTransform
    net.minecraft.client.renderer.ItemInHandRenderer#applyItemArmAttackTransform
    net.minecraft.client.renderer.block.model.ItemTransform#apply
    net.minecraft.client.renderer.entity.player.PlayerRenderer#renderHand
    net.minecraft.client.model.HumanoidModel#createMesh
    net.minecraft.client.model.PlayerModel#createMesh

The stick proxy is preview-only and uses the transform inherited from
assets/minecraft/models/item/handheld.json:
    firstperson_righthand rotation [0, -90, 25], translation [1.13, 3.2, 1.13],
    scale [0.68, 0.68, 0.68]

The block proxy is preview-only and uses the transform inherited from
assets/minecraft/models/block/block.json:
    firstperson_righthand rotation [0, 45, 0], translation [0, 0, 0],
    scale [0.40, 0.40, 0.40]

The Blender scene is rotated +90 degrees around X for authoring, so the camera
looks toward +Y in world space. Export-only anchor empties keep the unrotated
Minecraft render-space matrices for tools/export_viewmodel_pose.py. The export
anchors are neutral hand-space anchors; Minecraft's item renderer should apply
per-model first-person display transforms at runtime.
"""

from __future__ import annotations

from math import radians
from pathlib import Path

import bpy
from mathutils import Matrix, Vector


OUT_PATH = Path(__file__).with_name("minecraft_1_21_1_empty_hand_viewmodel.blend")
PIXEL = 1.0 / 16.0
BLENDER_AUTHORING_MATRIX = Matrix.Rotation(radians(90.0), 4, "X")


def clear_scene() -> None:
    bpy.ops.object.select_all(action="SELECT")
    bpy.ops.object.delete()


def mat_translate(x: float, y: float, z: float) -> Matrix:
    return Matrix.Translation((x, y, z))


def mat_rot_x(deg: float) -> Matrix:
    return Matrix.Rotation(radians(deg), 4, "X")


def mat_rot_y(deg: float) -> Matrix:
    return Matrix.Rotation(radians(deg), 4, "Y")


def mat_rot_z(deg: float) -> Matrix:
    return Matrix.Rotation(radians(deg), 4, "Z")


def mat_scale(x: float, y: float, z: float) -> Matrix:
    matrix = Matrix.Identity(4)
    matrix[0][0] = x
    matrix[1][1] = y
    matrix[2][2] = z
    return matrix


def make_material(name: str, color: tuple[float, float, float, float]) -> bpy.types.Material:
    mat = bpy.data.materials.new(name)
    mat.diffuse_color = color
    return mat


def add_box(
    name: str,
    origin_px: tuple[float, float, float],
    size_px: tuple[float, float, float],
    transform: Matrix,
    material: bpy.types.Material,
) -> bpy.types.Object:
    ox, oy, oz = origin_px
    sx, sy, sz = size_px
    corners = [
        (ox, oy, oz),
        (ox + sx, oy, oz),
        (ox + sx, oy + sy, oz),
        (ox, oy + sy, oz),
        (ox, oy, oz + sz),
        (ox + sx, oy, oz + sz),
        (ox + sx, oy + sy, oz + sz),
        (ox, oy + sy, oz + sz),
    ]
    verts = [(transform @ Vector((x * PIXEL, y * PIXEL, z * PIXEL, 1.0))).to_3d() for x, y, z in corners]
    faces = [
        (0, 1, 2, 3),
        (5, 4, 7, 6),
        (4, 0, 3, 7),
        (1, 5, 6, 2),
        (3, 2, 6, 7),
        (4, 5, 1, 0),
    ]
    mesh = bpy.data.meshes.new(f"{name}Mesh")
    mesh.from_pydata([tuple(v) for v in verts], [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    obj.data.materials.append(material)
    bpy.context.collection.objects.link(obj)
    return obj


def add_empty(name: str, transform: Matrix, display_size: float = 0.18) -> bpy.types.Object:
    obj = bpy.data.objects.new(name, None)
    obj.empty_display_type = "ARROWS"
    obj.empty_display_size = display_size
    obj.matrix_world = transform
    bpy.context.collection.objects.link(obj)
    return obj


def add_export_empty(name: str, minecraft_transform: Matrix, display_size: float = 0.1) -> bpy.types.Object:
    obj = add_empty(name, minecraft_transform, display_size)
    obj.hide_viewport = True
    obj.hide_render = True
    return obj


def create_viewmodel_armature(item_transform: Matrix, right_arm_transform: Matrix, left_arm_transform: Matrix) -> bpy.types.Object:
    armature_data = bpy.data.armatures.new("viewmodel_armature")
    armature_data.display_type = "STICK"
    armature = bpy.data.objects.new("viewmodel_armature", armature_data)
    bpy.context.collection.objects.link(armature)
    bpy.context.view_layer.objects.active = armature
    armature.select_set(True)
    bpy.ops.object.mode_set(mode="EDIT")

    camera_bone = armature_data.edit_bones.new("camera")
    camera_bone.head = (0.0, 0.0, 0.0)
    camera_bone.tail = (0.0, 0.0, -0.35)

    right_arm_head = right_arm_transform.to_translation()
    right_arm_dir = right_arm_transform.to_3x3() @ Vector((0.0, 0.35, 0.0))
    right_viewmodel_arm = armature_data.edit_bones.new("viewmodel_arm_R")
    right_viewmodel_arm.head = right_arm_head
    right_viewmodel_arm.tail = right_arm_head + right_arm_dir.normalized() * 0.35
    right_viewmodel_arm.parent = camera_bone
    right_viewmodel_arm.use_connect = False

    left_arm_head = left_arm_transform.to_translation()
    left_arm_dir = left_arm_transform.to_3x3() @ Vector((0.0, 0.35, 0.0))
    left_viewmodel_arm = armature_data.edit_bones.new("viewmodel_arm_L")
    left_viewmodel_arm.head = left_arm_head
    left_viewmodel_arm.tail = left_arm_head + left_arm_dir.normalized() * 0.35
    left_viewmodel_arm.parent = camera_bone
    left_viewmodel_arm.use_connect = False

    item_head = item_transform.to_translation()
    item_dir = item_transform.to_3x3() @ Vector((0.0, 0.25, 0.0))
    item_root = armature_data.edit_bones.new("item_root")
    item_root.head = item_head
    item_root.tail = item_head + item_dir.normalized() * 0.25
    item_root.parent = camera_bone
    item_root.use_connect = False

    bpy.ops.object.mode_set(mode="OBJECT")
    for bone_name in ("viewmodel_arm_R", "viewmodel_arm_L"):
        constraint = armature.pose.bones[bone_name].constraints.new(type="CHILD_OF")
        constraint.name = "Child Of item_root"
        constraint.target = armature
        constraint.subtarget = "item_root"
        constraint.inverse_matrix = armature.pose.bones["item_root"].matrix.inverted() @ armature.pose.bones[bone_name].matrix
    armature.show_in_front = True
    return armature


def bind_mesh_to_bone(obj: bpy.types.Object, armature: bpy.types.Object, bone_name: str) -> None:
    if obj.type != "MESH":
        return

    group = obj.vertex_groups.new(name=bone_name)
    group.add([vertex.index for vertex in obj.data.vertices], 1.0, "ADD")
    modifier = obj.modifiers.new("viewmodel_armature", "ARMATURE")
    modifier.object = armature


def parent_object_to_bone_preserve_world(obj: bpy.types.Object, armature: bpy.types.Object, bone_name: str) -> None:
    world_matrix = obj.matrix_world.copy()
    obj.parent = armature
    obj.parent_type = "BONE"
    obj.parent_bone = bone_name
    obj.matrix_world = world_matrix


def add_wire_frustum(camera: bpy.types.Object, fov_deg: float, aspect: float, distance: float) -> None:
    half_h = distance * __import__("math").tan(radians(fov_deg) / 2.0)
    half_w = half_h * aspect
    verts = [
        (0.0, 0.0, 0.0),
        (-half_w, -half_h, -distance),
        (half_w, -half_h, -distance),
        (half_w, half_h, -distance),
        (-half_w, half_h, -distance),
    ]
    edges = [(0, 1), (0, 2), (0, 3), (0, 4), (1, 2), (2, 3), (3, 4), (4, 1)]
    mesh = bpy.data.meshes.new("MinecraftCameraFrustumMesh")
    mesh.from_pydata(verts, edges, [])
    mesh.update()
    obj = bpy.data.objects.new("Minecraft camera view - 70 deg FOV, 16:9", mesh)
    obj.display_type = "WIRE"
    obj.hide_render = True
    bpy.context.collection.objects.link(obj)
    obj.parent = camera


def create_scene() -> None:
    clear_scene()

    skin = make_material("base skin placeholder", (0.73, 0.49, 0.34, 1.0))
    stick = make_material("minecraft stick proxy", (0.46, 0.28, 0.11, 1.0))
    block = make_material("minecraft block proxy", (0.39, 0.29, 0.18, 1.0))
    sleeve = make_material("outer sleeve placeholder", (0.12, 0.30, 0.82, 0.38))
    sleeve.use_nodes = True
    bsdf = sleeve.node_tree.nodes.get("Principled BSDF")
    bsdf.inputs["Alpha"].default_value = 0.38
    sleeve.blend_method = "BLEND"

    # Vanilla idle values: empty ItemStack, no swing, equip progress 0.
    right_f = 1.0
    hand_matrix = Matrix.Identity(4)
    hand_matrix @= mat_translate(right_f * 0.64000005, -0.6, -0.71999997)
    hand_matrix @= mat_rot_y(right_f * 45.0)
    hand_matrix @= mat_translate(right_f * -1.0, 3.6, 3.5)
    hand_matrix @= mat_rot_z(right_f * 120.0)
    hand_matrix @= mat_rot_x(200.0)
    hand_matrix @= mat_rot_y(right_f * -135.0)
    hand_matrix @= mat_translate(right_f * 5.6, 0.0, 0.0)

    left_f = -1.0
    left_hand_matrix = Matrix.Identity(4)
    left_hand_matrix @= mat_translate(left_f * 0.64000005, -0.6, -0.71999997)
    left_hand_matrix @= mat_rot_y(left_f * 45.0)
    left_hand_matrix @= mat_translate(left_f * -1.0, 3.6, 3.5)
    left_hand_matrix @= mat_rot_z(left_f * 120.0)
    left_hand_matrix @= mat_rot_x(200.0)
    left_hand_matrix @= mat_rot_y(left_f * -135.0)
    left_hand_matrix @= mat_translate(left_f * 5.6, 0.0, 0.0)

    # ModelPart.translateAndRotate for the wide right arm: PartPose.offset(-5, 2, 0).
    right_arm_part = mat_translate(-5.0 * PIXEL, 2.0 * PIXEL, 0.0)
    arm_matrix = hand_matrix @ right_arm_part
    left_arm_part = mat_translate(5.0 * PIXEL, 2.0 * PIXEL, 0.0)
    left_arm_matrix = left_hand_matrix @ left_arm_part

    # Minecraft applies this transform before PlayerRenderer.renderRightHand(), which then applies
    # the right_arm ModelPart offset internally.
    add_export_empty("viewmodel_arm_R_transform_anchor", hand_matrix)
    arm_anchor = add_empty("viewmodel_arm_R_transform_anchor_display", BLENDER_AUTHORING_MATRIX @ hand_matrix, 0.12)
    left_arm_anchor = add_empty("viewmodel_arm_L_transform_anchor_display", BLENDER_AUTHORING_MATRIX @ left_hand_matrix, 0.12)
    arm_matrix = BLENDER_AUTHORING_MATRIX @ arm_matrix
    left_arm_matrix = BLENDER_AUTHORING_MATRIX @ left_arm_matrix
    right_arm_base = add_box("right_arm_base_wide", (-3.0, -2.0, -2.0), (4.0, 12.0, 4.0), arm_matrix, skin)
    left_arm_base = add_box("left_arm_base_wide", (-1.0, -2.0, -2.0), (4.0, 12.0, 4.0), left_arm_matrix, skin)

    # PlayerModel wide sleeves use CubeDeformation.extend(0.25).
    right_sleeve = add_box("right_sleeve_outer_wide", (-3.25, -2.25, -2.25), (4.5, 12.5, 4.5), arm_matrix, sleeve)
    left_sleeve = add_box("left_sleeve_outer_wide", (-1.25, -2.25, -2.25), (4.5, 12.5, 4.5), left_arm_matrix, sleeve)

    # Vanilla held item path for a right main hand, no swing, equip progress 0:
    #   ItemInHandRenderer.renderArmWithItem
    #   -> applyItemArmTransform
    #   -> applyItemArmAttackTransform
    #   -> ItemRenderer.render
    #   -> handheld.json firstperson_righthand display transform
    item_hand_matrix = Matrix.Identity(4)
    item_hand_matrix @= mat_translate(0.56, -0.52, -0.72)
    item_hand_matrix @= mat_rot_y(45.0)
    item_hand_matrix @= mat_rot_z(0.0)
    item_hand_matrix @= mat_rot_x(0.0)
    item_hand_matrix @= mat_rot_y(-45.0)

    add_export_empty("held_item_transform_anchor_firstperson_righthand", item_hand_matrix)
    item_anchor_matrix = BLENDER_AUTHORING_MATRIX @ item_hand_matrix
    item_anchor = add_empty("held_item_transform_anchor_firstperson_righthand_display", item_anchor_matrix)

    item_preview_matrix = item_anchor_matrix.copy()
    item_preview_matrix @= mat_translate(1.13 * PIXEL, 3.2 * PIXEL, 1.13 * PIXEL)
    item_preview_matrix @= mat_rot_x(0.0)
    item_preview_matrix @= mat_rot_y(-90.0)
    item_preview_matrix @= mat_rot_z(25.0)
    item_preview_matrix @= mat_scale(0.68, 0.68, 0.68)
    item_preview_anchor = add_empty("preview_handheld_firstperson_righthand_display", item_preview_matrix, 0.12)

    # ItemRenderer translates baked model vertices by -0.5 after applying display transforms.
    # Parent or align preview baked-model geometry to this empty if the model uses 0..1 block coordinates.
    baked_item_matrix = item_preview_matrix @ mat_translate(-0.5, -0.5, -0.5)
    item_baked_origin = add_empty("held_item_baked_model_origin_after_minus_0_5", baked_item_matrix, 0.12)

    # Simple preview stick proxy in generated-item pixel space. This is not an export anchor.
    stick_proxy = add_box("minecraft_stick_proxy", (7.0, 1.0, 7.5), (2.0, 14.0, 1.0), baked_item_matrix, stick)

    # Neutral block item animation anchor. The block display transform below is preview-only.
    add_export_empty("held_block_transform_anchor_firstperson_righthand", item_hand_matrix)
    block_anchor_matrix = BLENDER_AUTHORING_MATRIX @ item_hand_matrix
    block_anchor = add_empty("held_block_transform_anchor_firstperson_righthand_display", block_anchor_matrix)

    block_preview_matrix = block_anchor_matrix.copy()
    block_preview_matrix @= mat_translate(0.0, 0.0, 0.0)
    block_preview_matrix @= mat_rot_x(0.0)
    block_preview_matrix @= mat_rot_y(45.0)
    block_preview_matrix @= mat_rot_z(0.0)
    block_preview_matrix @= mat_scale(0.40, 0.40, 0.40)
    block_preview_anchor = add_empty("preview_block_firstperson_righthand_display", block_preview_matrix, 0.12)

    block_baked_matrix = block_preview_matrix @ mat_translate(-0.5, -0.5, -0.5)
    block_baked_origin = add_empty("held_block_baked_model_origin_after_minus_0_5", block_baked_matrix, 0.12)
    block_proxy = add_box("minecraft_block_proxy", (0.0, 0.0, 0.0), (16.0, 16.0, 16.0), block_baked_matrix, block)

    camera_data = bpy.data.cameras.new("Minecraft first-person camera")
    camera_data.type = "PERSP"
    camera_data.angle = radians(70.0)
    camera_data.clip_start = 0.05
    camera_data.clip_end = 1000.0
    camera = bpy.data.objects.new("Minecraft first-person camera", camera_data)
    bpy.context.collection.objects.link(camera)
    camera.location = (0.0, 0.0, 0.0)
    camera.rotation_euler = (radians(90.0), 0.0, 0.0)
    bpy.context.scene.camera = camera
    add_wire_frustum(camera, 70.0, 16.0 / 9.0, 2.0)

    viewmodel_armature = create_viewmodel_armature(item_anchor_matrix, arm_matrix, left_arm_matrix)
    parent_object_to_bone_preserve_world(camera, viewmodel_armature, "camera")
    bind_mesh_to_bone(stick_proxy, viewmodel_armature, "item_root")
    bind_mesh_to_bone(block_proxy, viewmodel_armature, "item_root")
    bind_mesh_to_bone(right_arm_base, viewmodel_armature, "viewmodel_arm_R")
    bind_mesh_to_bone(right_sleeve, viewmodel_armature, "viewmodel_arm_R")
    bind_mesh_to_bone(left_arm_base, viewmodel_armature, "viewmodel_arm_L")
    bind_mesh_to_bone(left_sleeve, viewmodel_armature, "viewmodel_arm_L")
    parent_object_to_bone_preserve_world(item_anchor, viewmodel_armature, "item_root")
    parent_object_to_bone_preserve_world(item_preview_anchor, viewmodel_armature, "item_root")
    parent_object_to_bone_preserve_world(item_baked_origin, viewmodel_armature, "item_root")
    parent_object_to_bone_preserve_world(block_anchor, viewmodel_armature, "item_root")
    parent_object_to_bone_preserve_world(block_preview_anchor, viewmodel_armature, "item_root")
    parent_object_to_bone_preserve_world(block_baked_origin, viewmodel_armature, "item_root")
    parent_object_to_bone_preserve_world(arm_anchor, viewmodel_armature, "viewmodel_arm_R")
    parent_object_to_bone_preserve_world(left_arm_anchor, viewmodel_armature, "viewmodel_arm_L")

    light = bpy.data.objects.new("viewmodel inspection light", bpy.data.lights.new("viewmodel inspection light", "AREA"))
    bpy.context.collection.objects.link(light)
    light.location = (0.0, -1.0, 1.5)
    light.data.energy = 300.0
    light.data.size = 3.0

    bpy.context.scene.render.resolution_x = 1920
    bpy.context.scene.render.resolution_y = 1080
    bpy.context.scene.unit_settings.system = "METRIC"

    note = bpy.data.texts.new("SOURCE_NOTES")
    note.write(
        "Generated from Minecraft 1.21.1 NeoForm transformed sources. "
        "The visible scene is rotated +90 degrees around X so the camera looks toward +Y in Blender; "
        "hidden export anchors preserve Minecraft render-space coordinates, where the camera looks down -Z. "
        "the arm transform is the idle empty-main-hand path from ItemInHandRenderer.renderPlayerArm. "
        "The held item/block export empties are neutral hand-space anchors. "
        "Preview-only child empties simulate minecraft:item/handheld and minecraft:block/block first-person display transforms. "
        "The armature hierarchy is camera -> item_root, camera -> viewmodel_arm_R, and camera -> viewmodel_arm_L, "
        "with both viewmodel arms constrained Child Of item_root. "
        "Item/block proxy meshes are weighted to item_root; arm and sleeve meshes are weighted to their matching viewmodel arm bones. "
        "No custom mod renderer exists in src/main/java, so this is vanilla 1.21.1 behavior."
    )

    bpy.ops.wm.save_as_mainfile(filepath=str(OUT_PATH))


if __name__ == "__main__":
    create_scene()
