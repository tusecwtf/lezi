//! BN-05: accepted metadata and startup use the same dimension domain.
use super::*;

fn stage_as(fx: &CausalFx, principal: &Principal, item: &mut CausalMediaItem) {
    let bytes = vec![0; item.byte_size as usize];
    item.sha256 = hex::encode(Sha256::digest(&bytes));
    fx.store
        .stage_test_preimage(
            principal,
            &item.media_uuid,
            &bytes,
            &item.sha256,
            1_700_000_000,
            DEFAULT_CAUSAL_MEDIA_STAGING_LIMITS,
        )
        .unwrap();
}

fn validate_reopened(fx: &CausalFx) {
    let reopened = Store::open(fx._dir.path().join("lezi.db")).unwrap();
    reopened
        .validate_authority_graph(1024, |family, media, size| {
            let bytes = fs::read(fx._dir.path().join("media").join(family).join(media))?;
            Ok(bytes.len() == size)
        })
        .unwrap();
}

#[test]
fn authorized_media_dimension_boundaries_commit_replay_and_restart_for_every_owner_kind() {
    for (kind, role) in [("record", "log"), ("baby", "avatar"), ("care_plan", "plan")] {
        for width in [None, Some(1), Some(i64::from(i32::MAX))] {
            for height in [None, Some(1), Some(i64::from(i32::MAX))] {
                let fx = CausalFx::new();
                // Baby remains Owner-only; ordinary members own their record/plan.
                let principal = if kind == "baby" {
                    &fx.owner
                } else {
                    &fx.member
                };
                let id = Uuid::new_v4();
                let mut media = h39_media(role, 1);
                media.width = width;
                media.height = height;
                stage_as(&fx, principal, &mut media);
                let mut unit = mut_unit(
                    kind,
                    id,
                    None,
                    h39_root(kind, fx.baby_id, "valid metadata", 100, Some(&media)),
                    false,
                );
                unit.media = vec![media.clone()];
                let result = fx.commit(principal, unit.clone(), 1_700_000_001).unwrap();
                assert_eq!(
                    result.results[0].status, "accepted",
                    "{kind} {width:?}/{height:?}"
                );
                assert_eq!(result.results[0].stable_media, vec![media.clone()]);
                let replay = fx.commit(principal, unit, 1_700_000_002).unwrap();
                assert!(replay.results[0].replay);
                assert_eq!(replay.results[0].stable_media, vec![media]);
                validate_reopened(&fx);
            }
        }
    }
}

#[test]
fn invalid_dimensions_leave_all_causal_publication_state_unchanged() {
    for (kind, role) in [("record", "log"), ("baby", "avatar"), ("care_plan", "plan")] {
        for invalid in [0, -1, i64::from(i32::MAX) + 1, i64::MAX] {
            for field in ["width", "height"] {
                let fx = CausalFx::new();
                // Baby remains Owner-only; ordinary members own their record/plan.
                let principal = if kind == "baby" {
                    &fx.owner
                } else {
                    &fx.member
                };
                let id = Uuid::new_v4();
                let mut media = h39_media(role, 1);
                if field == "width" {
                    media.width = Some(invalid);
                } else {
                    media.height = Some(invalid);
                }
                stage_as(&fx, principal, &mut media);
                let before = resolution_durable_state(&fx.store, &fx.family_id, "", id);
                let mut unit = mut_unit(
                    kind,
                    id,
                    None,
                    h39_root(kind, fx.baby_id, "invalid metadata", 100, Some(&media)),
                    false,
                );
                unit.media = vec![media];
                assert_commit_rejected(fx.commit(principal, unit, 1_700_000_001), "invalid_domain");
                assert_eq!(
                    resolution_durable_state(&fx.store, &fx.family_id, "", id),
                    before,
                    "{kind} {field}={invalid}"
                );
                validate_reopened(&fx);
            }
        }
    }
}
