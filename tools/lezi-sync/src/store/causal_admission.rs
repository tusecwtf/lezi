use rusqlite::{params, Transaction};

use super::StoreError;

/// Maximum durable, unresolved branch versions retained for one causal root.
pub(super) const MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT: usize = 64;
const DEFAULT_CAUSAL_COMMIT_PRINCIPAL_LIMIT: u32 = 120;
const DEFAULT_CAUSAL_COMMIT_FAMILY_LIMIT: u32 = 1_200;
const DEFAULT_CAUSAL_COMMIT_WINDOW_SECONDS: i64 = 60;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct CausalAdmissionConfig {
    pub principal_commit_limit: u32,
    pub family_commit_limit: u32,
    pub window_seconds: i64,
    pub max_open_branches_per_root: usize,
}

impl Default for CausalAdmissionConfig {
    fn default() -> Self {
        Self {
            principal_commit_limit: DEFAULT_CAUSAL_COMMIT_PRINCIPAL_LIMIT,
            family_commit_limit: DEFAULT_CAUSAL_COMMIT_FAMILY_LIMIT,
            window_seconds: DEFAULT_CAUSAL_COMMIT_WINDOW_SECONDS,
            max_open_branches_per_root: MAX_OPEN_CAUSAL_BRANCHES_PER_ROOT,
        }
    }
}

impl CausalAdmissionConfig {
    pub(crate) fn validate(self) -> Result<Self, StoreError> {
        if self.principal_commit_limit == 0
            || self.family_commit_limit == 0
            || self.window_seconds <= 0
            || self.max_open_branches_per_root == 0
        {
            return Err(StoreError::InvalidCausalAdmissionConfig);
        }
        Ok(self)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CausalCommitSaturation {
    Principal,
    Family,
    OpenBranch,
}

impl CausalCommitSaturation {
    pub(crate) const fn code(self) -> &'static str {
        match self {
            Self::Principal => "causal_commit_principal_rate_limited",
            Self::Family => "causal_commit_family_rate_limited",
            Self::OpenBranch => "causal_open_branch_limit_reached",
        }
    }

    pub(crate) const fn scope(self) -> &'static str {
        match self {
            Self::Principal => "principal",
            Self::Family => "family",
            Self::OpenBranch => "root",
        }
    }
}

pub(super) fn admit_new_branch(
    transaction: &Transaction<'_>,
    family_id: &str,
    entity_type: &str,
    client_uuid: &str,
    max_open_branches_per_root: usize,
) -> Result<(), StoreError> {
    let open_branch_count: i64 = transaction.query_row(
        "SELECT COUNT(*)
         FROM conflicts AS conflict
         JOIN conflict_branches AS branch
           ON branch.family_id = conflict.family_id
          AND branch.conflict_id = conflict.conflict_id
         WHERE conflict.family_id = ?1
           AND conflict.entity_type = ?2
           AND conflict.client_uuid = ?3
           AND conflict.status = 'open'",
        params![family_id, entity_type, client_uuid],
        |row| row.get(0),
    )?;
    if open_branch_count >= max_open_branches_per_root as i64 {
        return Err(StoreError::CausalCommitSaturated(
            CausalCommitSaturation::OpenBranch,
        ));
    }
    Ok(())
}
