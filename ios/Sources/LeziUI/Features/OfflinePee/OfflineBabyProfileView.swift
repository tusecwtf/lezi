#if canImport(SwiftUI)
import SwiftUI
import LeziCore

@MainActor
struct OfflineBabyProfileView: View {
    @Environment(\.leziTheme) private var theme
    @ObservedObject var model: OfflineBabyProfileModel

    var body: some View {
        if let editor = model.editor {
            VStack(alignment: .leading, spacing: theme.density.sectionGap) {
                HStack {
                    LeziText("编辑宝宝档案", role: .title)
                    Spacer()
                    Button("关闭") { model.close() }
                        .disabled(editor.isBusy)
                        .accessibilityIdentifier("offline.profile.close")
                }
                .frame(minHeight: theme.primaryTouchMinimum)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("offline.profile.dragRegion")
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        LeziText("仅修改此宝宝的本机档案，不改变护理记录或当前宝宝。", role: .label)
                        TextField("昵称（不可重复）", text: Binding(
                            get: { editor.nickname }, set: model.editNickname
                        ))
                        .accessibilityIdentifier("offline.profile.nickname")
                        if let error = editor.nicknameError {
                            LeziText(error, role: .label).accessibilityIdentifier("offline.profile.nickname.error")
                        }
                        Picker("性别", selection: Binding<String?>(
                            get: { editor.sex }, set: model.editSex
                        )) {
                            if let value = editor.sex, value != "female", value != "male" {
                                Text("原值未识别，请重新选择").tag(Optional(value)).disabled(true)
                            }
                            Text("女宝").tag(Optional("female")).accessibilityIdentifier("offline.profile.sex.female")
                            Text("男宝").tag(Optional("male")).accessibilityIdentifier("offline.profile.sex.male")
                            Text("未设置").tag(nil as String?).accessibilityIdentifier("offline.profile.sex.unknown")
                        }
                        .pickerStyle(.menu)
                        .accessibilityIdentifier("offline.profile.sex")
                        if let error = editor.sexError {
                            LeziText(error, role: .label).accessibilityIdentifier("offline.profile.sex.error")
                        }
                        if let nativeDay = OfflineBabyBirthday.date(editor.birthday) {
                            DatePicker("出生日期", selection: Binding(
                                get: { nativeDay },
                                set: { model.editBirthday(OfflineBabyBirthday.day($0)) }
                            ), displayedComponents: .date)
                            .environment(\.calendar, OfflineBabyBirthday.calendar)
                            .environment(\.timeZone, OfflineBabyBirthday.calendar.timeZone)
                            .accessibilityIdentifier("offline.profile.birthday")
                        } else {
                            // Foundation's Gregorian calendar has a 1582 cutover,
                            // whereas Android LocalDate is proleptic Gregorian.
                            // Never silently replace such a valid stored day with
                            // a shifted date or today merely by opening an editor.
                            TextField("出生日期（YYYY-MM-DD）", text: Binding(
                                get: { editor.birthday }, set: model.editBirthday
                            ))
                            .accessibilityIdentifier("offline.profile.birthday.text")
                            LeziText("此日期以原始年月日保留，请按 YYYY-MM-DD 填写。", role: .label)
                        }
                        if let error = editor.birthdayError {
                            LeziText(error, role: .label).accessibilityIdentifier("offline.profile.birthday.error")
                        }
                        TextField("出生体重（kg，可选）", text: Binding(
                            get: { editor.weightText }, set: model.editWeight
                        ))
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                        .accessibilityIdentifier("offline.profile.weight")
                        LeziText("可填千克，保存时换算为克；留空为未设置。", role: .label)
                        if let error = editor.weightError {
                            LeziText(error, role: .label).accessibilityIdentifier("offline.profile.weight.error")
                        }
                        if let error = editor.formError {
                            LeziText(error, role: .label).accessibilityIdentifier("offline.profile.error")
                        }
                    }
                    .disabled(editor.isBusy)
                }
                .scrollDismissesKeyboard(.interactively)
                HStack {
                    Button("取消") { model.close() }
                        .frame(minHeight: theme.primaryTouchMinimum)
                        .accessibilityIdentifier("offline.profile.cancel")
                    Spacer()
                    Button(editor.isBusy ? "正在保存" : "保存档案") { model.save() }
                        .frame(minHeight: theme.primaryTouchMinimum)
                        .accessibilityIdentifier("offline.profile.save")
                }
                .disabled(editor.isBusy)
            }
            .padding(theme.pageInset)
            .interactiveDismissDisabled(editor.isDirty || editor.isBusy)
            .alert("放弃未保存的修改？", isPresented: $model.discardRequested) {
                Button("继续编辑", role: .cancel) { model.keepEditing() }
                    .accessibilityIdentifier("offline.profile.discard.keep")
                Button("放弃修改", role: .destructive) { model.discard() }
                    .accessibilityIdentifier("offline.profile.discard.confirm")
            } message: { Text("这些修改尚未写入本机。") }
        }
    }
}
#endif
