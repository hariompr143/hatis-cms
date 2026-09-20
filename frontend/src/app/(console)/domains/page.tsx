import { request, query } from '@/lib/api';
import { ErrorPanel } from '@/components/ErrorPanel';
import { EmptyState } from '@/components/EmptyState';
import { StatusBadge } from '@/components/StatusBadge';
import { formatRelativeTime } from '@/lib/format';
import { accessToken } from '@/lib/session';
import { PageResponse } from '@/lib/api';

export const metadata = { title: 'Domains — HATIS' };

interface DomainView {
  id: string;
  hostname: string;
  status: string;
  verificationMethod: string;
  dnsManagedByPlatform: boolean;
  certificateExpiresAt: string | null;
  lastError: string | null;
  lastCheckAt: string | null;
}

interface Project {
  id: string;
  name: string;
}

export default async function DomainsPage({
  searchParams,
}: {
  searchParams: Promise<{ project?: string }>;
}) {
  const params = await searchParams;
  const token = await accessToken();

  let projects: Project[] = [];
  let domains: DomainView[] = [];
  let error: unknown = null;

  try {
    const projectPage = await request<PageResponse<Project>>('/v1/projects?page=0&size=100', {
      accessToken: token ?? undefined,
    });
    projects = projectPage.items;

    const projectId = params.project ?? projects[0]?.id;
    if (projectId) {
      domains = await request<DomainView[]>(`/v1/domains${query({ projectId })}`, {
        accessToken: token ?? undefined,
      });
    }
  } catch (cause) {
    error = cause;
  }

  const selectedProject = params.project ?? projects[0]?.id ?? '';

  return (
    <>
      <div className="page-head">
        <h1>Domains</h1>
        <p className="muted">
          A domain only serves traffic after its DNS challenge has been verified and a certificate is
          ready.
        </p>
      </div>

      {error ? <ErrorPanel error={error} /> : null}

      <div className="card">
        <form method="GET">
          <label>
            Project
            <select name="project" defaultValue={selectedProject}>
              {projects.map((project) => (
                <option key={project.id} value={project.id}>
                  {project.name}
                </option>
              ))}
            </select>
          </label>
          <button type="submit">Filter</button>
        </form>
      </div>

      {domains.length === 0 && !error ? <EmptyState message="No domains bound to this project." /> : null}

      {domains.length > 0 ? (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>Hostname</th>
                <th>Status</th>
                <th>Verification</th>
                <th>DNS</th>
                <th>Certificate expires</th>
                <th>Checked</th>
              </tr>
            </thead>
            <tbody>
              {domains.map((domain) => (
                <tr key={domain.id}>
                  <td className="mono">{domain.hostname}</td>
                  <td>
                    <StatusBadge status={domain.status} />
                  </td>
                  <td className="muted">{domain.verificationMethod.replace(/_/g, ' ').toLowerCase()}</td>
                  <td>{domain.dnsManagedByPlatform ? 'platform' : 'customer'}</td>
                  <td className="muted">{formatRelativeTime(domain.certificateExpiresAt)}</td>
                  <td className="muted">{formatRelativeTime(domain.lastCheckAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}
