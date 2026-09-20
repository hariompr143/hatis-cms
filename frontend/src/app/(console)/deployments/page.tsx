import { PageResponse, request, query } from '@/lib/api';
import { ErrorPanel } from '@/components/ErrorPanel';
import { EmptyState } from '@/components/EmptyState';
import { StatusBadge } from '@/components/StatusBadge';
import { formatRelativeTime } from '@/lib/format';
import { accessToken } from '@/lib/session';

export const metadata = { title: 'Deployments — HATIS' };

interface ApplicationView {
  id: string;
  projectId: string;
  name: string;
  slug: string;
  runtime: string;
  defaultPort: number;
}

interface ReleaseView {
  id: string;
  version: string;
  image: string;
  digest: string | null;
  scanStatus: string;
  status: string;
  createdAt: string;
}

interface Project {
  id: string;
  name: string;
}

export default async function DeploymentsPage({
  searchParams,
}: {
  searchParams: Promise<{ project?: string; application?: string }>;
}) {
  const params = await searchParams;
  const token = await accessToken();

  let projects: Project[] = [];
  let applications: ApplicationView[] = [];
  let releases: ReleaseView[] = [];
  let error: unknown = null;

  try {
    const projectPage = await request<PageResponse<Project>>('/v1/projects?page=0&size=100', {
      accessToken: token ?? undefined,
    });
    projects = projectPage.items;

    const projectId = params.project ?? projects[0]?.id;
    if (projectId) {
      applications = await request<ApplicationView[]>(
        `/v1/applications${query({ projectId })}`,
        { accessToken: token ?? undefined },
      );

      const applicationId = params.application ?? applications[0]?.id;
      if (applicationId) {
        const releasePage = await request<PageResponse<ReleaseView>>(
          `/v1/releases${query({ applicationId, size: 25 })}`,
          { accessToken: token ?? undefined },
        );
        releases = releasePage.items;
      }
    }
  } catch (cause) {
    error = cause;
  }

  const selectedProject = params.project ?? projects[0]?.id ?? '';
  const selectedApplication = params.application ?? applications[0]?.id ?? '';

  return (
    <>
      <div className="page-head">
        <h1>Deployments</h1>
        <p className="muted">
          Only releases whose scan passed can be deployed. Rollouts are asynchronous: start one and poll
          the operation.
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
          <label>
            Application
            <select name="application" defaultValue={selectedApplication}>
              {applications.map((application) => (
                <option key={application.id} value={application.id}>
                  {application.name}
                </option>
              ))}
            </select>
          </label>
          <button type="submit">Filter</button>
        </form>
      </div>

      {applications.length > 0 ? (
        <div className="card">
          <h2>Applications</h2>
          <table>
            <thead>
              <tr>
                <th>Name</th>
                <th>Slug</th>
                <th>Runtime</th>
                <th>Port</th>
              </tr>
            </thead>
            <tbody>
              {applications.map((application) => (
                <tr key={application.id}>
                  <td>{application.name}</td>
                  <td className="mono">{application.slug}</td>
                  <td>{application.runtime}</td>
                  <td className="mono">{application.defaultPort}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {releases.length === 0 && !error ? <EmptyState message="No releases registered yet." /> : null}

      {releases.length > 0 ? (
        <div className="card">
          <h2>Releases</h2>
          <table>
            <thead>
              <tr>
                <th>Version</th>
                <th>Image</th>
                <th>Scan</th>
                <th>State</th>
                <th>Registered</th>
              </tr>
            </thead>
            <tbody>
              {releases.map((release) => (
                <tr key={release.id}>
                  <td className="mono">{release.version}</td>
                  <td className="mono">{release.digest ? `${release.image}@${release.digest.slice(0, 19)}…` : release.image}</td>
                  <td>
                    <StatusBadge status={release.scanStatus} />
                  </td>
                  <td>
                    <StatusBadge status={release.status} />
                  </td>
                  <td className="muted">{formatRelativeTime(release.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </>
  );
}
