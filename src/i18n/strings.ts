// SPDX-License-Identifier: Apache-2.0
// All user-facing text. Guidance text is resolved here from the rule identifier (TDD v1.0 11.2),
// so a translation or wording change never touches native code.
import type { RuleId, TrackingFailure } from '../../modules/guided-capture';

export type Lang = 'en' | 'si';

export interface RuleText {
  /** Short instruction shown in the banner. */
  short: string;
  /** Fuller explanation shown when the engine marks the message escalated. */
  detail?: string;
}

type RuleTable = Record<RuleId, RuleText>;

const rulesEn: RuleTable = {
  T1: {
    short: 'Tracking lost — move back to an area you have already scanned',
    detail: 'Point the phone at something you filmed a moment ago and hold it there until the view settles.',
  },
  T2: {
    short: 'Move the phone slowly from side to side',
    detail: 'The camera is learning the room. Keep moving gently — it cannot start while the phone is still.',
  },
  T3: { short: 'The phone is getting hot — finish the scan soon' },
  T4: { short: 'Another app is using the camera — close it and try again' },
  T5: { short: 'Something went wrong — please restart the scan' },
  T6: { short: "You're moving too fast" },
  G1: {
    short: 'Turn more slowly',
    detail: 'Fast turns leave no overlap between pictures. Take about two seconds to turn a quarter of the way.',
  },
  G2: { short: 'Hold the phone steady', detail: 'Hold it with both hands, close to your body.' },
  G3: {
    short: 'Keep moving forward',
    detail: 'Walk slowly along the walls. Every step gives the camera a new point of view.',
  },
  G4: {
    short: "Walk around the room, don't spin in place",
    detail:
      'Turning on the spot shows the room from only one point, so it cannot be measured in 3D. Take a few steps sideways while you film.',
  },
  L1: { short: "It's too dark here — turn on a light" },
  L2: { short: 'Too bright — avoid pointing at the window' },
  L3: {
    short: 'The image is blurry — move more slowly',
    detail: 'If you are already moving slowly, wipe the camera lens with a soft cloth.',
  },
  L4: { short: 'Clean the camera lens' },
  L5: { short: 'Wait for the camera to focus' },
  S1: { short: 'Point at a corner or an object, not a blank wall' },
  S2: { short: 'Too close — step back' },
  S3: { short: 'Too far — move closer' },
  S4: { short: 'Mirror or glass — move slowly past it' },
  C1: { short: 'Turn towards the unscanned side' },
  C2: { short: 'Look up and down too' },
  C3: { short: 'This area needs more views' },
  C4: { short: 'A corner is still missing' },
  C5: { short: 'Return to where you started' },
  C6: { short: 'This scan looks good — you can stop' },
} as RuleTable;

const rulesSi: RuleTable = {
  T1: {
    short: 'ට්‍රැකින් නැති වුණා — කලින් scan කරපු තැනකට ආපහු එන්න',
    detail: 'ටිකකට කලින් video කරපු දෙයකට phone එක එල්ල කරලා, තිරය ස්ථිර වෙනකම් තියාගන්න.',
  },
  T2: {
    short: 'phone එක හෙමින් දෙපැත්තට හරවන්න',
    detail: 'කැමරාව කාමරය හඳුනගන්නවා. phone එක නොසෙල්වී තිබ්බොත් පටන් ගන්න බෑ — හෙමින් move කරන්න.',
  },
  T3: { short: 'phone එක රත් වෙනවා — ඉක්මනින් scan එක ඉවර කරන්න' },
  T4: { short: 'වෙනත් app එකක් camera එක පාවිච්චි කරනවා — ඒක වහලා ආයෙ උත්සාහ කරන්න' },
  T5: { short: 'මොකක්හරි වැරැද්දක් — scan එක ආයෙ පටන් ගන්න' },
  T6: { short: 'ඔයා ගොඩක් ඉක්මනට move වෙනවා' },
  G1: {
    short: 'හෙමින් කරකවන්න',
    detail: 'ඉක්මනට හැරෙව්වොත් පින්තූර අතර overlap නැති වෙනවා. කාලක් හැරෙන්න තත්පර දෙකක් විතර ගන්න.',
  },
  G2: { short: 'phone එක ස්ථිරව තියාගන්න', detail: 'අත් දෙකෙන්ම, ඇඟට ළඟින් අල්ලගන්න.' },
  G3: { short: 'දිගටම ඉදිරියට යන්න', detail: 'බිත්ති දිගේ හෙමින් ඇවිදින්න. හැම පියවරක්ම අලුත් කෝණයක් දෙනවා.' },
  G4: {
    short: 'එක තැනක කරකැවෙන්න එපා — කාමරය වටා ඇවිදින්න',
    detail:
      'එක තැනක හිටගෙන කරකැවුණොත් කාමරය එක ලක්ෂයකින් විතරයි පේන්නේ, ඒ නිසා 3D මනින්න බෑ. video කරන ගමන් පැත්තට පියවර කිහිපයක් යන්න.',
  },
  L1: { short: 'ආලෝකය මදි — ලයිට් එකක් දාන්න' },
  L2: { short: 'ඉතා දීප්තිමත් — ජනේලයට එල්ල කරන්න එපා' },
  L3: { short: 'රූපය බොඳයි — හෙමින් යන්න', detail: 'දැනටමත් හෙමින් නම්, කැමරා කාචය පිරිසිදු කරන්න.' },
  L4: { short: 'කැමරා කාචය පිරිසිදු කරන්න' },
  L5: { short: 'focus වෙනකම් රැඳෙන්න' },
  S1: { short: 'හිස් බිත්තියක් — කොනක් හෝ භාණ්ඩයක් බලන්න' },
  S2: { short: 'ඉතා ළඟයි — පිටිපස්සට යන්න' },
  S3: { short: 'ඉතා දුරයි — ළං වෙන්න' },
  S4: { short: 'කැඩපත/වීදුරු — හෙමින් යන්න' },
  C1: { short: 'scan නොකරපු පැත්තට හැරෙන්න' },
  C2: { short: 'උඩත් පහළත් බලන්න' },
  C3: { short: 'මේ ප්‍රදේශයට තව කෝණ ඕනේ' },
  C4: { short: 'කොනක් තවම ඉතුරුයි' },
  C5: { short: 'පටන් ගත්ත තැනට ආපහු එන්න' },
  C6: { short: 'scan එක හොඳයි — දැන් නවත්තන්න පුළුවන්' },
} as RuleTable;

const ui = {
  en: {
    appTitle: 'Guided Capture',
    appSubtitle: 'Record a room so it can be rebuilt in 3D',
    checking: 'Checking this phone…',
    checkCamera: 'Camera permission',
    checkArcore: 'Google Play Services for AR',
    checkStorage: 'Free storage',
    grantCamera: 'Allow camera',
    installArcore: 'Install / update AR services',
    recheck: 'Check again',
    unsupported: 'This phone cannot run the scan. ARCore does not support this model, so a capture would not be usable.',
    cameraDenied: 'The camera is needed to film the room. Nothing is uploaded without your action.',
    startScan: 'Start scan',
    tipsTitle: 'Before you start',
    tips: [
      'Turn on the lights.',
      'Walk slowly along the walls — do not stand in the middle and spin.',
      'Keep corners and furniture in view; avoid blank walls and mirrors.',
    ],
    record: 'Record',
    stop: 'Stop',
    stopping: 'Finishing…',
    starting: 'Starting camera…',
    rec: 'REC',
    tracking: 'Tracking',
    trackingLost: 'Lost',
    initialising: 'Starting',
    oneMinuteLeft: 'One minute left',
    debug: 'Debug',
    trail: 'Your path',
    summaryTitle: 'Scan summary',
    verdictGOOD: 'Looks good',
    verdictCHECK: 'Usable, but check the warnings',
    verdictINCOMPLETE: 'Incomplete — please record again',
    duration: 'Duration',
    trackingLosses: 'Tracking losses',
    path: 'Distance walked',
    keyframes: 'Viewpoints',
    coverage: 'Coverage method',
    coverageNote: 'Path only — no completeness percentage is claimed yet.',
    warnings: 'Warnings shown',
    noWarnings: 'No warnings were needed.',
    recording: 'Video',
    recordingOk: 'Saved',
    recordingFailed: 'Not saved',
    useCapture: 'Use this capture',
    recordAgain: 'Record again',
    accepting: 'Packaging…',
    exportedTitle: 'Capture packaged',
    exportedBody: 'No upload server is configured yet, so send the package yourself (Drive, email or cable).',
    share: 'Share package',
    newScan: 'New scan',
    savedAt: 'Saved at',
    language: 'සිංහල',
    error: 'Error',
    endMax: 'Maximum session length reached — the scan was finished automatically.',
    interruptions: 'Interruptions',
    integrityNote: 'Includes checksums.sha256 so the receiver can verify every file.',
    deleteLocal: 'Delete from this phone',
    deleteTitle: 'Delete this capture?',
    deleteBody: 'The video and logs will be removed from the phone. Only do this after the package has been sent.',
    deleteConfirm: 'Delete',
    cancel: 'Cancel',
    deletedNote: 'Deleted from this phone.',
  },
  si: {
    appTitle: 'Guided Capture',
    appSubtitle: 'කාමරය 3D විදිහට හදන්න video කරන්න',
    checking: 'phone එක පරීක්ෂා කරනවා…',
    checkCamera: 'කැමරා අවසරය',
    checkArcore: 'Google Play Services for AR',
    checkStorage: 'ඉතිරි ඉඩ',
    grantCamera: 'කැමරාවට අවසර දෙන්න',
    installArcore: 'AR services install / update කරන්න',
    recheck: 'ආයෙ පරීක්ෂා කරන්න',
    unsupported: 'මේ phone එකෙන් scan කරන්න බෑ. ARCore මේ model එකට සහාය දක්වන්නේ නැහැ.',
    cameraDenied: 'කාමරය video කරන්න කැමරාව ඕනේ. ඔයා නොකර කිසිම දෙයක් upload වෙන්නේ නෑ.',
    startScan: 'Scan එක පටන් ගන්න',
    tipsTitle: 'පටන් ගන්න කලින්',
    tips: [
      'ලයිට් දාන්න.',
      'බිත්ති දිගේ හෙමින් ඇවිදින්න — මැද හිටගෙන කරකැවෙන්න එපා.',
      'කොන් සහ භාණ්ඩ පේන්න තියාගන්න; හිස් බිත්ති සහ කැඩපත් මඟ හරින්න.',
    ],
    record: 'Record',
    stop: 'නවත්තන්න',
    stopping: 'ඉවර කරනවා…',
    starting: 'කැමරාව පටන් ගන්නවා…',
    rec: 'REC',
    tracking: 'Tracking',
    trackingLost: 'නැති වුණා',
    initialising: 'පටන් ගන්නවා',
    oneMinuteLeft: 'තව විනාඩියයි',
    debug: 'Debug',
    trail: 'ඔයා ගිය මාර්ගය',
    summaryTitle: 'Scan සාරාංශය',
    verdictGOOD: 'හොඳයි',
    verdictCHECK: 'පාවිච්චි කරන්න පුළුවන්, warnings බලන්න',
    verdictINCOMPLETE: 'අසම්පූර්ණයි — ආයෙ record කරන්න',
    duration: 'කාලය',
    trackingLosses: 'Tracking නැති වුණු වාර',
    path: 'ඇවිද්ද දුර',
    keyframes: 'කෝණ ගණන',
    coverage: 'Coverage ක්‍රමය',
    coverageNote: 'මාර්ගය විතරයි — තවම සම්පූර්ණතා ප්‍රතිශතයක් කියන්නේ නෑ.',
    warnings: 'පෙන්නපු warnings',
    noWarnings: 'කිසිම warning එකක් ඕනේ වුණේ නෑ.',
    recording: 'Video',
    recordingOk: 'Save වුණා',
    recordingFailed: 'Save වුණේ නෑ',
    useCapture: 'මේක පාවිච්චි කරන්න',
    recordAgain: 'ආයෙ record කරන්න',
    accepting: 'Package කරනවා…',
    exportedTitle: 'Package එක සූදානම්',
    exportedBody: 'තවම upload server එකක් නැති නිසා package එක ඔයාම යවන්න (Drive, email, cable).',
    share: 'Package එක share කරන්න',
    newScan: 'අලුත් scan එකක්',
    savedAt: 'Save කරපු තැන',
    language: 'English',
    error: 'දෝෂයක්',
    endMax: 'උපරිම කාලය ඉවරයි — scan එක ස්වයංක්‍රීයව ඉවර කළා.',
    interruptions: 'බාධා',
    integrityNote: 'ලැබෙන කෙනාට හැම file එකක්ම තහවුරු කරන්න checksums.sha256 ඇතුළත් කරලා තියෙනවා.',
    deleteLocal: 'මේ phone එකෙන් මකන්න',
    deleteTitle: 'මේ capture එක මකන්නද?',
    deleteBody: 'video එක සහ logs phone එකෙන් අයින් වෙනවා. package එක යැව්වට පස්සේ විතරක් මේක කරන්න.',
    deleteConfirm: 'මකන්න',
    cancel: 'අවලංගු කරන්න',
    deletedNote: 'මේ phone එකෙන් මැකුවා.',
  },
};

export type UiKey = keyof typeof ui.en;

const failureHintEn: Record<TrackingFailure, string> = {
  NONE: '',
  EXCESSIVE_MOTION: 'You moved too fast.',
  INSUFFICIENT_FEATURES: 'Not enough detail in view — point at furniture or a corner.',
  INSUFFICIENT_LIGHT: 'It is too dark.',
  BAD_STATE: 'Restart the scan if this continues.',
  CAMERA_UNAVAILABLE: 'Another app is using the camera.',
};

const failureHintSi: Record<TrackingFailure, string> = {
  NONE: '',
  EXCESSIVE_MOTION: 'ඔයා ඉක්මනට move වුණා.',
  INSUFFICIENT_FEATURES: 'පේන්න තියෙන විස්තර මදි — භාණ්ඩයකට හෝ කොනකට එල්ල කරන්න.',
  INSUFFICIENT_LIGHT: 'ආලෝකය මදි.',
  BAD_STATE: 'දිගටම මෙහෙම නම් scan එක ආයෙ පටන් ගන්න.',
  CAMERA_UNAVAILABLE: 'වෙනත් app එකක් කැමරාව පාවිච්චි කරනවා.',
};

export const RULE_IDS: RuleId[] = [
  'T1', 'T2', 'T3', 'T4', 'T5', 'T6', 'G1', 'G2', 'G3', 'G4',
  'L1', 'L2', 'L3', 'L4', 'L5', 'S1', 'S2', 'S3', 'S4', 'C1', 'C2', 'C3', 'C4', 'C5', 'C6',
];

export function ruleText(lang: Lang, id: RuleId): RuleText {
  const table = lang === 'si' ? rulesSi : rulesEn;
  return table[id] ?? rulesEn[id] ?? { short: id };
}

export function uiText<K extends UiKey>(lang: Lang, key: K): (typeof ui.en)[K] {
  return (ui[lang] as typeof ui.en)[key] ?? ui.en[key];
}

export function failureHint(lang: Lang, f: TrackingFailure): string {
  return (lang === 'si' ? failureHintSi : failureHintEn)[f] ?? '';
}

export const __forTests = { rulesEn, rulesSi, ui };
